import json
import os

try:
    from tenacity import retry, stop_after_attempt, wait_random_exponential
    HAS_TENACITY = True
except ImportError:
    HAS_TENACITY = False

try:
    from openai import OpenAI
    HAS_OPENAI = True
except ImportError:
    OpenAI = None
    HAS_OPENAI = False

from default_prompt import default_prompt
from services.constants import IMAGE_EXTENSIONS
from services.llm_image import read_image_for_prompt, smart_resize_qwen2_5_vl
from services.llm_json_utils import parse_llm_json, repair_json_content, repair_malformed_llm_json
from services.llm_prompting import (
    build_user_content,
    categories_from_prompt,
    mark_last_sample_image_ephemeral,
    resolve_sample_dir,
)
from services.llm_samples import normalize_sample_for_prompt, prepare_multi_samples
from services.llm_shapes import filter_valid_shapes, labelme_payload, restore_coordinates_to_4points


def _completion(client, completion_args):
    if not HAS_TENACITY:
        return client.chat.completions.create(**completion_args)

    @retry(wait=wait_random_exponential(min=5, max=60), stop=stop_after_attempt(15))
    def completion_with_backoff(**kwargs):
        return client.chat.completions.create(**kwargs)

    return completion_with_backoff(**completion_args)


def _load_few_shot_messages(data_dir, sample_project, sample_enabled):
    sample_dir = resolve_sample_dir(data_dir, sample_project, sample_enabled)
    if not sample_dir or not os.path.exists(sample_dir):
        return []

    try:
        few_shot_messages = prepare_multi_samples(sample_dir)
        if few_shot_messages:
            mark_last_sample_image_ephemeral(few_shot_messages)
        print(f"Loaded {len(few_shot_messages) // 2} few-shot samples from {sample_dir}")
        return few_shot_messages
    except Exception as e:
        print(f"Error loading samples: {e}")
        return []


def _save_debug(project_path, img_name, prefix, content):
    debug_filename = f"{prefix}_{os.path.splitext(img_name)[0]}.txt"
    debug_path = os.path.join(project_path, debug_filename)
    with open(debug_path, 'w', encoding='utf-8') as f:
        f.write(content)
    return debug_filename, debug_path


def _save_annotation(project_path, img_name, json_data):
    save_path = os.path.join(project_path, os.path.splitext(img_name)[0] + '.json')
    with open(save_path, 'w', encoding='utf-8') as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)


def _parse_response_shapes(project_path, img_name, content, width, height, input_width, input_height):
    result_json, raw_content, clean_content = parse_llm_json(content)
    if result_json is None:
        debug_filename, debug_path = _save_debug(
            project_path,
            img_name,
            'debug_failed',
            f"Raw Content:\n{raw_content}\n\nCleaned Content:\n{clean_content}\n\n"
            "Error: JSON parsing failed after all attempts.",
        )
        print(f"Warning: JSON parsing failed for {img_name}. Debug info saved to {debug_path}")
        raise Exception(f"No JSON found in response. Debug saved to {debug_filename}")

    raw_shapes = result_json if isinstance(result_json, list) else result_json.get('shapes', [])
    restored_shapes = restore_coordinates_to_4points(
        raw_shapes,
        width,
        height,
        input_width,
        input_height,
    )
    processed_shapes = filter_valid_shapes(restored_shapes)

    if not processed_shapes:
        _debug_empty_shapes(project_path, img_name, raw_content, result_json, restored_shapes)

    return processed_shapes


def _debug_empty_shapes(project_path, img_name, raw_content, result_json, restored_shapes):
    _debug_filename, debug_path = _save_debug(
        project_path,
        img_name,
        'debug_empty',
        "Raw Content:\n"
        f"{raw_content}\n\nParsed JSON:\n{json.dumps(result_json, indent=2, ensure_ascii=False)}\n\n"
        f"Restored Shapes:\n{json.dumps(restored_shapes, indent=2, ensure_ascii=False)}\n\n"
        "Reason: No valid labels found matching list.",
    )
    print(f"Warning: No valid shapes for {img_name}. Debug info saved to {debug_path}")


def _process_image(client, model, system_prompt, few_shot_messages, categories_str, project_path, img_name):
    img_path = os.path.join(project_path, img_name)
    base64_image, width, height, input_width, input_height = read_image_for_prompt(img_path)
    messages = [
        {"role": "system", "content": system_prompt},
        *few_shot_messages,
        {"role": "user", "content": build_user_content(base64_image, input_width, input_height, categories_str)},
    ]
    response = _completion(client, {
        "model": model,
        "messages": messages,
        "temperature": 0.05,
        "frequency_penalty": 0.05,
    })

    content = response.choices[0].message.content
    processed_shapes = _parse_response_shapes(
        project_path,
        img_name,
        content,
        width,
        height,
        input_width,
        input_height,
    )
    if not processed_shapes:
        return False

    _save_annotation(project_path, img_name, labelme_payload(img_name, processed_shapes, width, height))
    return True


def background_auto_label_llm(task_manager, task_id, data_dir, project_name, api_key, base_url, model, prompt, sample_project=None, sample_enabled=True):
    with task_manager.lock:
        task = task_manager.tasks.get(task_id)
    if not task:
        return

    try:
        try:
            client = OpenAI(api_key=api_key, base_url=base_url)
        except Exception as e:
            with task_manager.lock:
                task['status'] = 'failed'
                task['error'] = f'Failed to init client: {str(e)}'
            return

        project_path = os.path.join(data_dir, project_name)
        images = [f for f in os.listdir(project_path) if f.lower().endswith(IMAGE_EXTENSIONS)]
        system_prompt = prompt or default_prompt
        few_shot_messages = _load_few_shot_messages(data_dir, sample_project, sample_enabled)
        categories_str = categories_from_prompt(system_prompt)

        task['total'] = len(images)
        count = 0
        errors = []

        for index, img_name in enumerate(images):
            if task.get('cancel'):
                break

            try:
                if _process_image(client, model, system_prompt, few_shot_messages, categories_str, project_path, img_name):
                    count += 1
            except Exception as e:
                print(f"LLM Error for {img_name}: {e}")
                errors.append(str(e))

            with task_manager.lock:
                task['progress'] = index + 1
                task['processed_count'] = count

        if not task.get('cancel'):
            with task_manager.lock:
                if count == 0 and errors:
                    task['status'] = 'failed'
                    task['error'] = f"All images failed. Last error: {errors[-1]}"
                else:
                    task['status'] = 'completed'

    except Exception as e:
        print(f"Auto Label LLM Fatal Error: {e}")
        with task_manager.lock:
            task['status'] = 'failed'
            task['error'] = str(e)
