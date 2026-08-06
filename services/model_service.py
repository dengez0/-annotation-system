import os


MODEL_EXTENSIONS = ('.pt', '.onnx', '.engine')


def list_models(models_dir):
    models = []
    if os.path.exists(models_dir):
        for filename in os.listdir(models_dir):
            if filename.lower().endswith(MODEL_EXTENSIONS):
                models.append(filename)
    return models


def save_model_file(models_dir, file, custom_name=None):
    if not file or file.filename == '':
        return {'error': 'No selected file'}, 400

    original_filename = os.path.basename(file.filename)
    filename = original_filename

    if custom_name and custom_name.strip():
        custom_name = os.path.basename(custom_name.strip())
        _, orig_ext = os.path.splitext(original_filename)

        if not custom_name.lower().endswith(orig_ext.lower()):
            if not os.path.splitext(custom_name)[1]:
                custom_name += orig_ext
            elif os.path.splitext(custom_name)[1].lower() != orig_ext.lower():
                return {'error': f'Extension mismatch. Please keep {orig_ext}'}, 400

        filename = custom_name

    file.save(os.path.join(models_dir, filename))
    return {'status': 'success'}, 200


def delete_model(models_dir, model_name):
    if not model_name or '..' in model_name or '/' in model_name or '\\' in model_name:
        return {'error': 'Invalid model name'}, 400

    model_path = os.path.join(models_dir, model_name)
    if not os.path.exists(model_path):
        return {'error': 'Model not found'}, 404

    try:
        os.remove(model_path)
        base_name = os.path.splitext(model_name)[0]
        for ext in ['.txt', '.yaml']:
            sidecar = os.path.join(models_dir, base_name + ext)
            if os.path.exists(sidecar):
                os.remove(sidecar)
        return {'status': 'success'}, 200
    except Exception as e:
        return {'error': str(e)}, 500
