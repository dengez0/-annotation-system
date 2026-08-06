import ast
import json
import re


def clean_llm_content(content):
    content = re.sub(r'<think>.*?</think>', '', content, flags=re.DOTALL)
    if '```json' in content:
        content = content.split('```json')[1].split('```')[0]
    elif '```' in content:
        content = content.split('```')[1].split('```')[0]
    return content.strip()


def repair_json_content(json_str):
    try:
        json_str = json_str.replace("True", "true").replace("False", "false").replace("None", "null")

        quote_count = 0
        escape = False
        for char in json_str:
            if char == '\\':
                escape = not escape
            elif char == '"' and not escape:
                quote_count += 1
                escape = False
            else:
                escape = False

        if quote_count % 2 != 0:
            json_str += '"'

        json_str = re.sub(r',\s*([\]\}])', r'\1', json_str)
        json_str = re.sub(r',\s*$', '', json_str)

        stack = []
        for char in json_str:
            if char == '{':
                stack.append('}')
            elif char == '[':
                stack.append(']')
            elif char in ('}', ']') and stack and stack[-1] == char:
                stack.pop()

        while stack:
            json_str += stack.pop()

        return json_str
    except Exception:
        return json_str


def repair_malformed_llm_json(content):
    content = re.sub(r'":\s*":\s*\[', '": [', content)
    if '":":[{' in content:
        content = content.replace('":":[{', '":[{')
    content = re.sub(r'":\s*":\s*\[', '": [', content)
    content = re.sub(
        r'"points"\s*:\s*(?!\[\[)(\[[^\]]+\]),\s*(\[[^\]]+\]),\s*(\[[^\]]+\]),\s*(\[[^\]]+\])(\s*\])?',
        r'"points": [\1, \2, \3, \4]',
        content,
    )
    return content


def _parse_from_first_json_start(clean_content):
    match = re.search(r'[\[\{]', clean_content)
    if not match:
        return None

    candidate = clean_content[match.start():]
    try:
        decoder = json.JSONDecoder()
        result_json, _ = decoder.raw_decode(candidate)
        return result_json
    except Exception:
        pass

    try:
        start_char = candidate[0]
        end_char = '}' if start_char == '{' else ']'
        end_idx = candidate.rfind(end_char)
        if end_idx == -1:
            return None

        sub_candidate = candidate[:end_idx + 1]
        try:
            return json.loads(sub_candidate)
        except Exception:
            return ast.literal_eval(sub_candidate)
    except Exception:
        return None


def parse_llm_json(content):
    content = clean_llm_content(content)
    clean_content = repair_malformed_llm_json(content).strip()

    try:
        return json.loads(clean_content), content, clean_content
    except Exception:
        pass

    result_json = _parse_from_first_json_start(clean_content)
    if result_json is not None:
        return result_json, content, clean_content

    try:
        return ast.literal_eval(clean_content), content, clean_content
    except Exception:
        pass

    try:
        return json.loads(repair_json_content(clean_content)), content, clean_content
    except Exception:
        pass

    match = re.search(r'[\[\{]', clean_content)
    if match:
        try:
            repaired_partial = repair_json_content(clean_content[match.start():])
            return json.loads(repaired_partial), content, clean_content
        except Exception:
            pass

    return None, content, clean_content
