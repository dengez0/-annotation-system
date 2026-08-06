import os
import re

DEFAULT_CATEGORIES = "['without_helmet', 'helmet', 'smoking', 'walkie', 'cup', 'mobilephone', 'mask', 'closeeyes', 'yawn', 'unwear_uniform', 'uniform', 'fire', 'deckopen', 'deckclose', 'life', 'unwear_life', 'extinguisher']"


def categories_from_prompt(prompt):
    try:
        match = re.search(r'# Valid Labels List\s*(\[[\s\S]*?\])', prompt)
        if match:
            return match.group(1).replace('\n', ' ').strip()
    except Exception as e:
        print(f"Error parsing labels from prompt: {e}")
    return DEFAULT_CATEGORIES


def resolve_sample_dir(data_dir, sample_project, sample_enabled):
    if not sample_enabled:
        return None
    if sample_project and sample_project != "samples":
        return os.path.join(data_dir, sample_project)
    return os.path.join(data_dir, "samples")


def mark_last_sample_image_ephemeral(few_shot_messages):
    for msg in reversed(few_shot_messages):
        if msg['role'] == 'user' and isinstance(msg.get('content'), list):
            if msg['content']:
                msg['content'][-1]['cache_control'] = {"type": "ephemeral"}
            break


def build_user_content(base64_image, input_width, input_height, categories_str):
    return [
        {
            "type": "text",
            "text": (
                f"Analyze this image (Model input size: {input_width}x{input_height}). "
                f"Find ALL objects belonging to these categories: {categories_str}. "
                "Fully frame each object with a bounding box (include all extremities). "
                "Return the JSON object."
            ),
        },
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"}},
    ]
