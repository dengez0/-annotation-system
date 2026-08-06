import base64
import math

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None
    HAS_PIL = False


def smart_resize_qwen2_5_vl(img, min_pixels=32 * 32 * 4, max_pixels=2560 * 32 * 32):
    width, height = img.size
    h_bar = round(height / 32) * 32
    w_bar = round(width / 32) * 32

    if h_bar * w_bar > max_pixels:
        beta = math.sqrt((height * width) / max_pixels)
        h_bar = math.floor(height / beta / 32) * 32
        w_bar = math.floor(width / beta / 32) * 32
    elif h_bar * w_bar < min_pixels:
        beta = math.sqrt(min_pixels / (height * width))
        h_bar = math.ceil(height * beta / 32) * 32
        w_bar = math.ceil(width * beta / 32) * 32

    return h_bar, w_bar


def encode_image_file(img_path):
    with open(img_path, "rb") as image_file:
        return base64.b64encode(image_file.read()).decode('utf-8')


def read_image_for_prompt(img_path):
    base64_image = encode_image_file(img_path)
    width, height = 0, 0
    input_width, input_height = 0, 0

    if HAS_PIL:
        with Image.open(img_path) as img:
            width, height = img.size
            input_height, input_width = smart_resize_qwen2_5_vl(img)

    if not input_width or not input_height:
        input_width, input_height = width, height

    return base64_image, width, height, input_width, input_height
