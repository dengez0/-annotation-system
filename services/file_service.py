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
