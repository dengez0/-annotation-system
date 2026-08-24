# main.py
# pip install fastapi uvicorn ultralytics opencv-python-headless pillow numpy pydantic python-multipart python-dotenv
# 使用 "python main.py" 命令直接运行此服务

import sys
import os
import site

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
print(f"Current working directory: {os.getcwd()}")
print(f"sys.path: {sys.path}")

def _prepare_windows_cuda_dll_path():
    candidates = []
    for sp in site.getsitepackages():
        candidates.extend([
            os.path.join(sp, "nvidia", "cudnn", "bin"),
            os.path.join(sp, "nvidia", "cublas", "bin"),
            os.path.join(sp, "nvidia", "cuda_nvrtc", "bin"),
            os.path.join(sp, "torch", "lib"),
        ])
    existing = [p for p in candidates if os.path.isdir(p)]
    if not existing:
        return
    os.environ["PATH"] = ";".join(existing) + ";" + os.environ.get("PATH", "")
    add_dll_directory = getattr(os, "add_dll_directory", None)
    if add_dll_directory:
        for p in existing:
            try:
                add_dll_directory(p)
            except OSError:
                pass

_prepare_windows_cuda_dll_path()

try:
    from ultralytics.models import yolo
    from ultralytics.nn import modules
    sys.modules['models.yolo'] = yolo
    sys.modules['models.common'] = modules
    import ultralytics
    print(f"Loaded ultralytics from: {ultralytics.__file__}")
except ImportError:
    pass

import base64
import cv2
import numpy as np
import uvicorn
import logging
import json
import shutil
import time
import math

# 设置matplotlib后端为非交互式，避免初始化失败
import matplotlib
matplotlib.use('Agg')

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect, UploadFile, File, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO
from typing import List

# --- 1. 全局配置 ---
logging.basicConfig(level=logging.INFO)
MODELS_DIR = os.path.abspath(os.environ.get(
    "SIMPLELABEL_MODELS_DIR", os.path.join(BASE_DIR, "models")
))
SELECT_PICTURE_DIR = os.path.abspath(os.environ.get(
    "SIMPLELABEL_MODEL_DETECTION_OUTPUT_DIR",
    os.path.join(os.environ.get("SIMPLELABEL_PROCESSED_DIR", BASE_DIR), "model_detection"),
))
os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs(SELECT_PICTURE_DIR, exist_ok=True)
logging.info(f"模型持久化目录: {MODELS_DIR}")
logging.info(f"检测图片持久化目录: {SELECT_PICTURE_DIR}")
FONT_PATH = os.path.join(BASE_DIR, "simhei.ttf")
COLORS = [
    (239, 98, 98), (107, 203, 119), (102, 168, 242), (255, 192, 106),
    (158, 119, 232), (111, 224, 222), (255, 136, 199), (227, 169, 137)
]

# --- 2. 模型缓存与加载 ---
loaded_models = {}
model_profiles = {}
SUPPORTED_MODEL_EXTENSIONS = (".pt", ".onnx")

def _ensure_models_dir():
    os.makedirs(MODELS_DIR, exist_ok=True)

def _decode_base64_image(image_base64: str):
    header, encoded_data = image_base64.split(",", 1)
    image_bytes = base64.b64decode(encoded_data)
    return cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)

def _decode_base64_image_safe(image_base64: str):
    try:
        header, encoded_data = image_base64.split(",", 1)
        if not encoded_data:
            logging.warning("接收到不完整的Base64数据（数据部分为空），已跳过。")
            return None
        image_bytes = base64.b64decode(encoded_data)
        if not image_bytes:
            logging.warning("Base64解码后数据为空，已跳过。")
            return None
    except (ValueError, TypeError, IndexError) as e:
        logging.warning(f"无法解析Base64字符串: {e}，已跳过。")
        return None
    image_cv2 = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
    if image_cv2 is None:
        logging.warning("cv2.imdecode未能解码图像，可能数据已损坏，已跳过。")
        return None
    return image_cv2

def _encode_image_to_data_uri(image_cv2: np.ndarray) -> str:
    _, buffer = cv2.imencode('.jpg', image_cv2)
    result_base64 = base64.b64encode(buffer).decode("utf-8")
    return f"data:image/jpeg;base64,{result_base64}"

def _predict_and_encode(model: YOLO, image_cv2: np.ndarray, conf: float, iou: float, verbose: bool = False, line_width: int = 3) -> str:
    prediction_results = model.predict(source=image_cv2, conf=conf, iou=iou, verbose=verbose)
    annotated_image = draw_annotations(image_cv2, prediction_results, line_width)
    return _encode_image_to_data_uri(annotated_image)

def _predict_count_and_encode(model: YOLO, image_cv2: np.ndarray, conf: float, iou: float, verbose: bool = False, line_width: int = 3):
    prediction_results = model.predict(source=image_cv2, conf=conf, iou=iou, verbose=verbose)
    detection_count = 0
    raw_boxes = []
    h, w = image_cv2.shape[:2]
    if prediction_results and len(prediction_results) > 0 and getattr(prediction_results[0], "boxes", None) is not None:
        result = prediction_results[0]
        detection_count = int(len(result.boxes))
        boxes = result.boxes
        class_names = result.names
        for box, class_id, conf_val in zip(boxes.xyxy.cpu().numpy(), boxes.cls.cpu().numpy().astype(int), boxes.conf.cpu().numpy()):
            if not np.all(np.isfinite(box)): continue
            x1, y1, x2, y2 = box.tolist()
            raw_boxes.append({
                "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                "class_id": int(class_id),
                "conf": float(conf_val),
                "class_name": class_names[int(class_id)] if int(class_id) in class_names else str(class_id)
            })

    annotated_image = draw_annotations(image_cv2, prediction_results, line_width)
    return _encode_image_to_data_uri(annotated_image), detection_count, raw_boxes, {"w": w, "h": h}


class ObjectTracker:
    """逐连接目标追踪器，使用同类最近中心点距离匹配，按一秒间隔计算欧氏距离"""

    def __init__(self, max_unseen_seconds=3.0, match_distance_px=150.0):
        self._next_id = 0
        self.tracks: dict = {}
        self.max_unseen_seconds = max_unseen_seconds
        self.match_distance_px = match_distance_px
        self._last_sample_time = 0.0

    def update(self, detections: list, timestamp: float) -> list:
        det_centers = []
        for d in detections:
            det_centers.append(((d["x1"] + d["x2"]) / 2.0, (d["y1"] + d["y2"]) / 2.0))

        tracks_by_class = {}
        for tid, t in self.tracks.items():
            tracks_by_class.setdefault(t["class_name"], []).append(tid)

        assigned = {}
        used_tids = set()
        for det_idx, d in enumerate(detections):
            candidates = tracks_by_class.get(d["class_name"], [])
            if not candidates:
                continue
            dcx, dcy = det_centers[det_idx]
            best_tid, best_dist = None, float("inf")
            for tid in candidates:
                if tid in used_tids:
                    continue
                tc = self.tracks[tid]
                dist = math.hypot(dcx - tc["center_x"], dcy - tc["center_y"])
                if dist < best_dist and dist < self.match_distance_px:
                    best_dist, best_tid = dist, tid
            if best_tid is not None:
                assigned[det_idx] = best_tid
                used_tids.add(best_tid)

        for det_idx, d in enumerate(detections):
            if det_idx not in assigned:
                tid = self._next_id
                self._next_id += 1
                assigned[det_idx] = tid
                self.tracks[tid] = {
                    "class_name": d["class_name"],
                    "center_x": det_centers[det_idx][0],
                    "center_y": det_centers[det_idx][1],
                    "last_seen": timestamp,
                    "last_sampled_center": None,
                    "last_sampled_time": None,
                    "distance_per_sec": None,
                }

        for det_idx, tid in assigned.items():
            t = self.tracks[tid]
            t["center_x"] = det_centers[det_idx][0]
            t["center_y"] = det_centers[det_idx][1]
            t["last_seen"] = timestamp

        stale = [tid for tid, t in self.tracks.items()
                  if timestamp - t["last_seen"] > self.max_unseen_seconds]
        for tid in stale:
            del self.tracks[tid]

        result = []
        for det_idx, d in enumerate(detections):
            tid = assigned[det_idx]
            t = self.tracks[tid]
            result.append({
                "track_id": tid,
                "class_name": d["class_name"],
                "confidence": d["conf"],
                "center_x": t["center_x"],
                "center_y": t["center_y"],
                "distance_per_sec": t.get("distance_per_sec"),
            })
        return result

    def sample_and_calc_distance(self, timestamp: float):
        for t in self.tracks.values():
            if timestamp - t["last_seen"] > 1.2:
                continue
            if t["last_sampled_center"] is not None and t["last_sampled_time"] is not None:
                dx = t["center_x"] - t["last_sampled_center"][0]
                dy = t["center_y"] - t["last_sampled_center"][1]
                dt = timestamp - t["last_sampled_time"]
                if dt > 0:
                    t["distance_per_sec"] = math.hypot(dx, dy) / dt
            t["last_sampled_center"] = (t["center_x"], t["center_y"])
            t["last_sampled_time"] = timestamp


def _predict_with_tracking(model, image_cv2, conf, iou, tracker, timestamp,
                           sample_interval=1.0, verbose=False, line_width=3):
    prediction_results = model.predict(source=image_cv2, conf=conf, iou=iou, verbose=verbose)

    raw_boxes = []
    if prediction_results and len(prediction_results) > 0 and getattr(prediction_results[0], "boxes", None) is not None:
        result = prediction_results[0]
        class_names = result.names
        for box, class_id, conf_val in zip(
            result.boxes.xyxy.cpu().numpy(),
            result.boxes.cls.cpu().numpy().astype(int),
            result.boxes.conf.cpu().numpy()
        ):
            if not np.all(np.isfinite(box)):
                continue
            x1, y1, x2, y2 = box.tolist()
            raw_boxes.append({
                "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                "class_id": int(class_id),
                "conf": float(conf_val),
                "class_name": class_names[int(class_id)] if int(class_id) in class_names else str(class_id),
            })

    tracking_data = tracker.update(raw_boxes, timestamp)

    if timestamp - tracker._last_sample_time >= sample_interval:
        tracker.sample_and_calc_distance(timestamp)
        tracker._last_sample_time = timestamp

    annotated = draw_annotations(image_cv2, prediction_results, line_width, tracking_info=tracking_data)
    return _encode_image_to_data_uri(annotated), tracking_data


def _validate_model_filename(filename: str):
    if not filename:
        raise HTTPException(status_code=400, detail="文件名不能为空")
    if '..' in filename or '/' in filename or '\\' in filename:
        raise HTTPException(status_code=400, detail="非法文件名")

def _is_supported_model_file(filename: str) -> bool:
    return filename.lower().endswith(SUPPORTED_MODEL_EXTENSIONS)

def _shape_to_list(shape):
    values = []
    for dim in shape:
        dim_value = getattr(dim, "dim_value", None)
        dim_param = getattr(dim, "dim_param", None)
        if dim_value:
            values.append(int(dim_value))
        elif dim_param:
            values.append(str(dim_param))
        else:
            values.append(-1)
    return values

def _inspect_onnx_model(model_path: str):
    import onnx
    from onnx import checker

    model = onnx.load(model_path)
    checker.check_model(model)

    input_shapes = [_shape_to_list(x.type.tensor_type.shape.dim) for x in model.graph.input]
    output_shapes = [_shape_to_list(x.type.tensor_type.shape.dim) for x in model.graph.output]
    raw_multi_head = False
    if len(output_shapes) >= 3 and all(len(s) == 4 for s in output_shapes):
        head_ok = True
        spatial = []
        for s in output_shapes:
            if not isinstance(s[1], int) or s[1] <= 0 or s[1] % 3 != 0:
                head_ok = False
                break
            if not isinstance(s[2], int) or not isinstance(s[3], int):
                head_ok = False
                break
            spatial.append((s[2], s[3]))
        if head_ok:
            raw_multi_head = all(spatial[i][0] >= spatial[i + 1][0] and spatial[i][1] >= spatial[i + 1][1] for i in range(len(spatial) - 1))

    profile = {
        "path": model_path,
        "ir_version": int(model.ir_version),
        "opset": [(x.domain or "ai.onnx", int(x.version)) for x in model.opset_import],
        "inputs": input_shapes,
        "outputs": output_shapes,
        "is_raw_multi_head": raw_multi_head,
    }
    return profile

def get_model(model_name: str) -> YOLO:
    """根据模型名称动态加载模型，并使用缓存。"""
    if model_name not in loaded_models:
        model_path = os.path.join(MODELS_DIR, model_name)
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"模型文件未找到: {model_path}。")
        if not _is_supported_model_file(model_name):
            raise HTTPException(status_code=400, detail=f"只支持{', '.join(SUPPORTED_MODEL_EXTENSIONS)}格式的模型文件")
        logging.info(f"正在从 '{model_path}' 加载新模型...")
        if model_name.lower().endswith(".onnx"):
            profile = _inspect_onnx_model(model_path)
            model_profiles[model_name] = profile
            logging.info(f"ONNX模型检查通过: inputs={profile['inputs']}, outputs={profile['outputs']}, raw_multi_head={profile['is_raw_multi_head']}")
            loaded_models[model_name] = YOLO(model_path, task="detect")
        else:
            loaded_models[model_name] = YOLO(model_path)
            model_profiles[model_name] = {"path": model_path, "type": "pt"}
        logging.info(f"模型 '{model_name}' 加载并缓存成功！")
    return loaded_models[model_name]

try:
    font = ImageFont.truetype(FONT_PATH, 20)
    logging.info(f"成功加载字体: {FONT_PATH}")
except IOError:
    logging.warning(f"警告: 字体文件 '{FONT_PATH}' 未找到。")
    font = ImageFont.load_default()

# --- 3. Pydantic 数据模型 ---
class DetectionRequest(BaseModel):
    model_name: str = Field(..., description="要使用的模型文件名")
    image_base64: str
    conf: float = Field(..., ge=0, le=100)
    iou: float = Field(..., ge=0, le=100)
    line_width: int = Field(3, ge=1, le=10)

class DetectionResponse(BaseModel):
    image_base64: str

class BatchDetectionRequest(BaseModel):
    model_name: str = Field(..., description="要使用的模型文件名")
    images_base64: List[str]
    conf: float = Field(..., ge=0, le=100)
    iou: float = Field(..., ge=0, le=100)
    line_width: int = Field(3, ge=1, le=10)
    save_detected: bool = Field(False, description="是否保存检测出结果的图片")

class BatchDetectionResponse(BaseModel):
    results: List[str]
    detection_counts: List[int]
    raw_boxes: List[List[dict]]
    image_dims: List[dict]
    total: int
    processed: int
    success: int
    failed: int
    progress_percent: int

# --- 4. FastAPI 应用初始化 ---
app = FastAPI(
    title="实时 YOLO 检测 API",
    description="支持 HTTP 图片检测和 WebSocket 视频流检测。",
    version="4.3.0",
)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

# 挂载静态文件目录
app.mount("/asset", StaticFiles(directory=os.path.join(BASE_DIR, "asset")), name="asset")

# --- 5. 核心辅助函数：图像标注 ---
def draw_annotations(image: np.ndarray, results, line_width: int = 3,
                     tracking_info: list = None) -> np.ndarray:
    """在图像上绘制检测框和类别标签，可选显示追踪ID和距离信息。"""
    h, w = image.shape[:2]
    frame_pil = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(frame_pil)
    result = results[0]
    boxes = result.boxes
    if len(boxes) == 0: return image
    class_ids = boxes.cls.cpu().numpy().astype(int)
    confidences = boxes.conf.cpu().numpy()
    class_names = result.names
    box_coords = boxes.xyxy.cpu().numpy()
    for i, (box, class_id, conf) in enumerate(zip(box_coords, class_ids, confidences)):
        if not np.all(np.isfinite(box)):
            continue
        x1, y1, x2, y2 = box.tolist()
        x1, x2 = sorted((x1, x2))
        y1, y2 = sorted((y1, y2))
        x1 = max(0, min(int(round(x1)), w - 1))
        y1 = max(0, min(int(round(y1)), h - 1))
        x2 = max(0, min(int(round(x2)), w - 1))
        y2 = max(0, min(int(round(y2)), h - 1))
        if x2 <= x1 or y2 <= y1:
            continue
        color = COLORS[class_id % len(COLORS)]
        draw.rectangle([(x1, y1), (x2, y2)], outline=color, width=line_width)
        class_name = class_names[class_id] if class_id in class_names else str(class_id)

        if tracking_info and i < len(tracking_info):
            ti = tracking_info[i]
            track_id = ti.get("track_id", "?")
            label = f"ID{track_id} {class_name}: {conf:.2f}"
        else:
            label = f"{class_name}: {conf:.2f}"

        try: text_bbox = draw.textbbox((x1, y1), label, font=font)
        except AttributeError: text_width, text_height = draw.textsize(label, font=font); text_bbox = (x1, y1, x1 + text_width, y1 + text_height)
        text_height = text_bbox[3] - text_bbox[1]
        label_width = text_bbox[2] - text_bbox[0]
        label_y1 = y1 - text_height - 5 if y1 - text_height - 5 > 0 else y1 + 5
        label_x2 = min(x1 + label_width, w - 1)
        label_y2 = min(label_y1 + text_height, h - 1)
        if label_x2 > x1 and label_y2 > label_y1:
            draw.rectangle([(x1, label_y1), (label_x2, label_y2)], fill=color)
        draw.text((x1, label_y1), label, font=font, fill=(255, 255, 255))
    return cv2.cvtColor(np.array(frame_pil), cv2.COLOR_RGB2BGR)

# --- 6. API 端点实现 ---

@app.get("/api/models", summary="获取可用模型列表")
def get_models():
    """获取models目录下所有可用的模型文件。"""
    try:
        model_files = []
        if os.path.exists(MODELS_DIR):
            model_files = [file for file in os.listdir(MODELS_DIR) if _is_supported_model_file(file)]
        model_files.sort()
        return {"models": model_files}
    except Exception as e:
        logging.error(f"获取模型列表错误: {e}")
        raise HTTPException(status_code=500, detail=f"获取模型列表失败: {str(e)}")

@app.post("/api/upload_model", summary="上传模型文件")
async def upload_model(file: UploadFile = File(...)):
    """上传自定义模型文件到models目录。"""
    try:
        logging.info(f"开始上传模型文件: {file.filename}")
        
        if not _is_supported_model_file(file.filename):
            raise HTTPException(status_code=400, detail=f"只支持{', '.join(SUPPORTED_MODEL_EXTENSIONS)}格式的模型文件")
        
        _ensure_models_dir()
        
        file_path = os.path.join(MODELS_DIR, file.filename)
        logging.info(f"目标文件路径: {file_path}")
        
        # 如果文件已存在，先删除
        if os.path.exists(file_path):
            logging.info(f"删除已存在的文件: {file_path}")
            os.remove(file_path)
        
        # 写入文件
        logging.info(f"开始写入文件...")
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        
        # 验证文件是否成功写入
        if not os.path.exists(file_path):
            raise HTTPException(status_code=500, detail="文件写入失败")
        
        file_size = os.path.getsize(file_path)
        if file.filename.lower().endswith(".onnx"):
            profile = _inspect_onnx_model(file_path)
            model_profiles[file.filename] = profile
            logging.info(f"上传ONNX模型检查通过: inputs={profile['inputs']}, outputs={profile['outputs']}, raw_multi_head={profile['is_raw_multi_head']}")
        logging.info(f"模型文件上传成功: {file.filename}, 大小: {file_size / 1024 / 1024:.2f} MB")
        return {"message": "模型上传成功", "filename": file.filename, "size_mb": round(file_size / 1024 / 1024, 2)}
    except HTTPException:
        raise
    except Exception as e:
        logging.error(f"模型上传错误: {type(e).__name__}: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"模型上传失败: {str(e)}")

@app.delete("/api/delete_model", summary="删除模型文件")
async def delete_model(filename: str):
    """删除指定的模型文件。"""
    try:
        _validate_model_filename(filename)
        
        file_path = os.path.join(MODELS_DIR, filename)
        
        if not os.path.exists(file_path):
            raise HTTPException(status_code=404, detail=f"模型文件不存在: {filename}")
        
        # 从缓存中移除模型
        if filename in loaded_models:
            del loaded_models[filename]
            logging.info(f"从缓存中移除模型: {filename}")
        if filename in model_profiles:
            del model_profiles[filename]
        
        # 删除文件
        os.remove(file_path)
        logging.info(f"模型文件已删除: {filename}")
        
        return {"message": "模型删除成功", "filename": filename}
    except HTTPException:
        raise
    except Exception as e:
        logging.error(f"删除模型错误: {type(e).__name__}: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"删除模型失败: {str(e)}")

@app.post("/api/detect", response_model=DetectionResponse, summary="HTTP 图片检测")
async def detect_image_http(request: DetectionRequest):
    """处理单张图片的检测请求。"""
    try:
        model = get_model(request.model_name)
        image_cv2 = _decode_base64_image_safe(request.image_base64)
        if image_cv2 is None:
            raise HTTPException(status_code=400, detail="无效的图片数据")
        result_uri = _predict_and_encode(model, image_cv2, request.conf / 100, request.iou / 100, line_width=request.line_width)
        return {"image_base64": result_uri}
    except FileNotFoundError as e: raise HTTPException(status_code=404, detail=str(e))
    except Exception as e: logging.error(f"HTTP处理错误: {e}"); raise HTTPException(status_code=500, detail=f"服务器内部错误: {str(e)}")

@app.post("/api/batch_detect", response_model=BatchDetectionResponse, summary="批量图片检测")
async def detect_images_batch(request: BatchDetectionRequest):
    """批量处理多张图片的检测请求。"""
    try:
        model = get_model(request.model_name)
        results = []
        detection_counts = []
        raw_boxes_list = []
        image_dims_list = []
        total = len(request.images_base64)
        processed = 0
        success = 0
        failed = 0
        
        for idx, image_base64 in enumerate(request.images_base64):
            try:
                image_cv2 = _decode_base64_image_safe(image_base64)
                
                if image_cv2 is None:
                    results.append("")
                    detection_counts.append(0)
                    raw_boxes_list.append([])
                    image_dims_list.append({"w": 0, "h": 0})
                    processed += 1
                    failed += 1
                    logging.warning(f"图片 {idx+1} 解码失败")
                    continue
                
                try:
                    result_uri, detection_count, raw_boxes, img_dims = _predict_count_and_encode(model, image_cv2, request.conf / 100, request.iou / 100, verbose=False, line_width=request.line_width)
                    results.append(result_uri)
                    detection_counts.append(detection_count)
                    raw_boxes_list.append(raw_boxes)
                    image_dims_list.append(img_dims)
                    processed += 1
                    success += 1
                    if request.save_detected and detection_count > 0:
                        try:
                            timestamp = time.strftime("%Y%m%d_%H%M%S")
                            safe_name = f"detected_{timestamp}_{idx}.jpg"
                            save_path = os.path.join(SELECT_PICTURE_DIR, safe_name)
                            cv2.imwrite(save_path, image_cv2)
                            logging.info(f"图片已采集: {save_path}")
                        except Exception as e:
                            logging.warning(f"保存采集图片失败: {e}")
                except Exception as e:
                    logging.error(f"图片 {idx+1} 检测失败: {e}", exc_info=True)
                    results.append("")
                    detection_counts.append(0)
                    raw_boxes_list.append([])
                    image_dims_list.append({"w": 0, "h": 0})
                    processed += 1
                    failed += 1
            except Exception as e:
                logging.error(f"处理第{idx+1}张图片时出错: {e}")
                results.append("")
                detection_counts.append(0)
                raw_boxes_list.append([])
                image_dims_list.append({"w": 0, "h": 0})
                processed += 1
                failed += 1
        
        progress_percent = int(round((processed / total) * 100)) if total > 0 else 0
        return {
            "results": results,
            "detection_counts": detection_counts,
            "raw_boxes": raw_boxes_list,
            "image_dims": image_dims_list,
            "total": total,
            "processed": processed,
            "success": success,
            "failed": failed,
            "progress_percent": progress_percent
        }
    except FileNotFoundError as e: 
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e: 
        logging.error(f"批量检测错误: {e}")
        raise HTTPException(status_code=500, detail=f"服务器内部错误: {str(e)}")


@app.websocket("/ws/video_detection")
async def detect_video_websocket(websocket: WebSocket):
    """通过 WebSocket 处理实时视频帧检测，支持目标追踪与距离计算。"""
    await websocket.accept()
    logging.info("WebSocket 连接已建立（目标追踪与距离计算已启用）。")
    tracker = ObjectTracker()
    try:
        while True:
            data_str = await websocket.receive_text()
            data = json.loads(data_str)

            model_name = data['model_name']
            base64_str = data['image_base64']
            conf = data.get('conf', 50) / 100.0
            iou = data.get('iou', 45) / 100.0
            line_width = data.get('line_width', 3)
            enable_tracking = data.get('enable_tracking', True)

            model = get_model(model_name)

            image_cv2 = _decode_base64_image_safe(base64_str)
            if image_cv2 is None:
                continue

            timestamp = time.time()

            if enable_tracking:
                result_uri, tracking_data = _predict_with_tracking(
                    model, image_cv2, conf, iou, tracker, timestamp,
                    sample_interval=1.0, verbose=False, line_width=line_width
                )
                await websocket.send_json({
                    "image_base64": result_uri,
                    "tracking": tracking_data,
                    "timestamp": timestamp,
                })
            else:
                result_uri = _predict_and_encode(model, image_cv2, conf, iou, verbose=False, line_width=line_width)
                await websocket.send_json({"image_base64": result_uri})

    except WebSocketDisconnect:
        logging.info("WebSocket 客户端断开连接。")
    except Exception as e:
        error_message = f"WebSocket 处理错误: {type(e).__name__}"
        logging.error(f"{error_message} - {e}")
        await websocket.close(code=1011, reason=error_message)


@app.get("/", summary="检测页面")
def read_root():
    """返回主检测页面"""
    html_path = os.path.join(os.path.dirname(__file__), "model_detection.html")
    if os.path.exists(html_path):
        return FileResponse(html_path)
    return {"status": "YOLO detection API is running. Open model_detection.html manually."}

# --- 7. 服务启动 ---
if __name__ == "__main__":
    uvicorn.run("main:app", host=os.environ.get("SIMPLELABEL_MODEL_DETECTION_BIND", "0.0.0.0"),
                port=int(os.environ.get("SIMPLELABEL_MODEL_DETECTION_PORT", "8000")), reload=False)
