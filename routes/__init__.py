from routes.annotation_routes import annotation_bp
from routes.auto_label_routes import auto_label_bp
from routes.file_routes import file_bp
from routes.model_routes import model_bp
from routes.task_routes import task_bp


def register_blueprints(app):
    app.register_blueprint(annotation_bp)
    app.register_blueprint(file_bp)
    app.register_blueprint(model_bp)
    app.register_blueprint(task_bp)
    app.register_blueprint(auto_label_bp)

