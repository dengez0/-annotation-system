# Ultralytics YOLO 🚀, AGPL-3.0 license

import itertools

from ultralytics.data import build_yolo_dataset
from ultralytics.models import yolo
from ultralytics.nn.tasks import DetectionModel
from ultralytics.utils import DEFAULT_CFG, RANK, checks
from ultralytics.utils.torch_utils import de_parallel


def on_pretrain_routine_end(trainer):
    """Callback."""
    if RANK in {-1, 0}:
        # NOTE: for evaluation
        names = [name.split("/")[0] for name in list(trainer.test_loader.dataset.data["names"].values())]
        de_parallel(trainer.ema.ema).set_classes(names, cache_clip_model=False)
    device = next(trainer.model.parameters()).device
    trainer.text_model, _ = trainer.clip.load("ViT-B/32", device=device)
    for p in trainer.text_model.parameters():
        p.requires_grad_(False)


class WorldTrainer(yolo.detect.DetectionTrainer):
    """
    A class to fine-tune a world model on a close-set dataset.

    Example:
        ```python
        from ultralytics.models.yolo.world import WorldModel

        args = dict(model="yolov8s-world.pt", data="coco8.yaml", epochs=3)
        trainer = WorldTrainer(overrides=args)
        trainer.train()
        ```
    """

    def __init__(self, cfg=DEFAULT_CFG, overrides=None, _callbacks=None):
        """Initialize a WorldTrainer object with given arguments."""
        if overrides is None:
            overrides = {}
        super().__init__(cfg, overrides, _callbacks)

        # Import and assign clip
        try:
            import clip
        except ImportError:
            checks.check_requirements("git+https://github.com/ultralytics/CLIP.git")
            import clip
        self.clip = clip

    def get_model(self, cfg=None, weights=None, verbose=True):
        """Return DetectionModel initialized with specified config and weights."""
        model = DetectionModel(cfg, nc=self.data["nc"], verbose=verbose and RANK == -1)
        if weights:
            model.load(weights)
        return model

    def build_dataset(self, img_path, mode="train", batch=None):
        """
        Build YOLO Dataset.

        Args:
            img_path (str): Path to the folder containing images.
            mode (str): `train` mode or `val` mode, users are able to customize different augmentations for each mode.
            batch (int, optional): Size of batches, this is for `rect`. Defaults to None.
        """
        gs = max(int(de_parallel(self.model).stride.max() if self.model else 0), 32)
        return build_yolo_dataset(
            self.args, img_path, batch, self.data, mode=mode, rect=mode == "val", stride=gs, multi_modal=mode == "train"
        )

    def preprocess_batch(self, batch):
        """Preprocesses a batch of images for training."""
        return super().preprocess_batch(batch)
