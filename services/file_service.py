import os
import shutil
import tempfile

from services.file_upload import upload_files


def rename_project(data_dir, old_name, new_name, level='main', main_folder=None):
    if level == 'sub':
        if not main_folder:
            return {'error': 'Missing main_folder for subfolder rename'}, 400
        old_path = os.path.join(data_dir, main_folder, old_name)
        new_path = os.path.join(data_dir, main_folder, new_name)
    else:
        old_path = os.path.join(data_dir, old_name)
        new_path = os.path.join(data_dir, new_name)

    if not os.path.exists(old_path):
        return {'error': 'Project not found'}, 404

    if os.path.exists(new_path):
        return {'error': 'New name already exists'}, 400

    try:
        os.rename(old_path, new_path)
        return {'status': 'success'}, 200
    except Exception as e:
        return {'error': str(e)}, 500


def delete_project(data_dir, main_folder, subfolder=None, level='main'):
    if level == 'sub':
        project_path = os.path.join(data_dir, main_folder, subfolder)
    else:
        project_path = os.path.join(data_dir, main_folder)

    if not os.path.exists(project_path):
        return {'error': 'Project not found'}, 404

    try:
        shutil.rmtree(project_path)
        return {'status': 'success'}, 200
    except Exception as e:
        return {'error': str(e)}, 500


def delete_files(data_dir, main_folder, subfolder, filenames):
    project_path = os.path.join(data_dir, main_folder, subfolder)
    if not os.path.exists(project_path):
        return {'error': 'Project not found'}, 404

    deleted = []
    errors = []
    for fname in filenames:
        fname = os.path.basename(fname)
        if not fname:
            continue

        img_path = os.path.join(project_path, fname)
        deleted_img = False
        if os.path.exists(img_path):
            try:
                os.remove(img_path)
                deleted_img = True
            except Exception:
                errors.append(fname)
                continue

        json_name = os.path.splitext(fname)[0] + '.json'
        json_path = os.path.join(project_path, json_name)
        if os.path.exists(json_path):
            try:
                os.remove(json_path)
            except Exception:
                pass

        if deleted_img:
            deleted.append(fname)

    return {'status': 'success', 'deleted': len(deleted), 'errors': errors}, 200


def move_files(data_dir, main_folder, subfolder, filenames, dest_folder='', dest_main='', dest_sub=''):
    if dest_main and dest_sub:
        dest_main = os.path.basename(dest_main)
        dest_sub = os.path.basename(dest_sub)
        dest_folder = os.path.join(data_dir, dest_main, dest_sub)

    if not dest_folder:
        return {'error': 'No destination folder specified'}, 400

    dest_folder = os.path.abspath(dest_folder)
    if not os.path.exists(dest_folder):
        try:
            os.makedirs(dest_folder, exist_ok=True)
        except Exception as e:
            return {'error': f'Cannot create destination folder: {str(e)}'}, 400

    project_path = os.path.join(data_dir, main_folder, subfolder)
    if not os.path.exists(project_path):
        return {'error': 'Project not found'}, 404

    moved = []
    errors = []
    for fname in filenames:
        fname = os.path.basename(fname)
        if not fname:
            continue

        img_path = os.path.join(project_path, fname)
        moved_img = False
        if os.path.exists(img_path):
            dest_img_path = os.path.join(dest_folder, fname)
            try:
                shutil.move(img_path, dest_img_path)
                moved_img = True
            except Exception:
                errors.append(fname)
                continue

        json_name = os.path.splitext(fname)[0] + '.json'
        json_path = os.path.join(project_path, json_name)
        if os.path.exists(json_path):
            dest_json_path = os.path.join(dest_folder, json_name)
            try:
                shutil.move(json_path, dest_json_path)
            except Exception:
                pass

        if moved_img:
            moved.append(fname)

    return {'status': 'success', 'moved': len(moved), 'errors': errors}, 200


def move_files_to_completed(data_dir, main_folder, subfolder, filenames):
    """Move completed annotations to moved image/<same subfolder> without overwriting."""
    if main_folder != 'annotation files':
        return {'error': 'Move is only available from annotation files'}, 403

    safe_subfolder = os.path.basename(subfolder)
    if not safe_subfolder or safe_subfolder != subfolder:
        return {'error': 'Invalid source subfolder'}, 400

    source_folder = os.path.join(data_dir, main_folder, safe_subfolder)
    if not os.path.isdir(source_folder):
        return {'error': 'Project not found'}, 404

    destination_folder = os.path.join(data_dir, 'moved image', safe_subfolder)
    try:
        os.makedirs(destination_folder, exist_ok=True)
    except OSError as exc:
        return {'error': f'Cannot create destination folder: {exc}'}, 400

    candidates = []
    skipped = []
    errors = []
    for original_name in filenames:
        filename = os.path.basename(str(original_name))
        if not filename or filename != original_name:
            errors.append({'name': str(original_name), 'reason': 'Invalid filename'})
            continue

        image_path = os.path.join(source_folder, filename)
        if not os.path.isfile(image_path):
            errors.append({'name': filename, 'reason': 'Source image not found'})
            continue

        json_name = os.path.splitext(filename)[0] + '.json'
        json_path = os.path.join(source_folder, json_name)
        destination_image = os.path.join(destination_folder, filename)
        destination_json = os.path.join(destination_folder, json_name)
        if os.path.exists(destination_image) or os.path.exists(destination_json):
            skipped.append({'name': filename, 'reason': 'Destination already contains the image or annotation'})
            continue

        candidates.append((filename, image_path, json_path, destination_image, destination_json))

    moved = []
    for filename, image_path, json_path, destination_image, destination_json in candidates:
        try:
            shutil.move(image_path, destination_image)
            if os.path.isfile(json_path):
                try:
                    shutil.move(json_path, destination_json)
                except OSError as exc:
                    # Keep a failed pair together whenever the image can be restored.
                    try:
                        shutil.move(destination_image, image_path)
                    except OSError:
                        pass
                    errors.append({'name': filename, 'reason': f'Annotation move failed: {exc}'})
                    continue
            moved.append(filename)
        except OSError as exc:
            errors.append({'name': filename, 'reason': f'Image move failed: {exc}'})

    return {
        'status': 'success',
        'destination': f'moved image/{safe_subfolder}',
        'moved': len(moved),
        'skipped': skipped,
        'errors': errors,
    }, 200


def restore_files_from_completed(data_dir, subfolder, filenames):
    """Restore moved images to annotation files/<same subfolder> without overwriting."""
    safe_subfolder = os.path.basename(subfolder)
    if not safe_subfolder or safe_subfolder != subfolder:
        return {'error': 'Invalid source subfolder'}, 400

    source_folder = os.path.join(data_dir, 'moved image', safe_subfolder)
    if not os.path.isdir(source_folder):
        return {'error': 'Moved project not found'}, 404

    destination_folder = os.path.join(data_dir, 'annotation files', safe_subfolder)
    try:
        os.makedirs(destination_folder, exist_ok=True)
    except OSError as exc:
        return {'error': f'Cannot create destination folder: {exc}'}, 400

    candidates = []
    skipped = []
    errors = []
    for original_name in filenames:
        filename = os.path.basename(str(original_name))
        if not filename or filename != original_name:
            errors.append({'name': str(original_name), 'reason': 'Invalid filename'})
            continue

        image_path = os.path.join(source_folder, filename)
        if not os.path.isfile(image_path):
            errors.append({'name': filename, 'reason': 'Source image not found'})
            continue

        json_name = os.path.splitext(filename)[0] + '.json'
        json_path = os.path.join(source_folder, json_name)
        destination_image = os.path.join(destination_folder, filename)
        destination_json = os.path.join(destination_folder, json_name)
        if os.path.exists(destination_image) or os.path.exists(destination_json):
            skipped.append({'name': filename, 'reason': 'Destination already contains the image or annotation'})
            continue

        candidates.append((filename, image_path, json_path, destination_image, destination_json))

    moved = []
    for filename, image_path, json_path, destination_image, destination_json in candidates:
        try:
            shutil.move(image_path, destination_image)
            if os.path.isfile(json_path):
                try:
                    shutil.move(json_path, destination_json)
                except OSError as exc:
                    # Keep a failed pair together whenever the image can be restored.
                    try:
                        shutil.move(destination_image, image_path)
                    except OSError:
                        pass
                    errors.append({'name': filename, 'reason': f'Annotation move failed: {exc}'})
                    continue
            moved.append(filename)
        except OSError as exc:
            errors.append({'name': filename, 'reason': f'Image move failed: {exc}'})

    return {
        'status': 'success',
        'destination': f'annotation files/{safe_subfolder}',
        'moved': len(moved),
        'skipped': skipped,
        'errors': errors,
    }, 200


def copy_files_to_paste(data_dir, main_folder, subfolder, filenames):
    project_path = os.path.join(data_dir, main_folder, subfolder)
    if not os.path.exists(project_path):
        return {'error': 'Project not found'}, 404

    dest_folder = os.path.join(data_dir, 'paste image')
    os.makedirs(dest_folder, exist_ok=True)

    copied = []
    errors = []
    for fname in filenames:
        fname = os.path.basename(fname)
        if not fname:
            continue

        img_path = os.path.join(project_path, fname)
        copied_img = False
        if os.path.exists(img_path):
            dest_img_path = os.path.join(dest_folder, fname)
            try:
                shutil.copy2(img_path, dest_img_path)
                copied_img = True
            except Exception:
                errors.append(fname)
                continue

        json_name = os.path.splitext(fname)[0] + '.json'
        json_path = os.path.join(project_path, json_name)
        if os.path.exists(json_path):
            dest_json_path = os.path.join(dest_folder, json_name)
            try:
                shutil.copy2(json_path, dest_json_path)
            except Exception:
                pass

        if copied_img:
            copied.append(fname)

    return {'status': 'success', 'copied': len(copied), 'errors': errors}, 200


def make_project_archive(project_path, archive_basename):
    temp_dir = tempfile.mkdtemp()
    archive_name = os.path.join(temp_dir, archive_basename)
    shutil.make_archive(archive_name, 'zip', project_path)
    return archive_name + '.zip'
