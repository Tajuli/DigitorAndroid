#!/usr/bin/env python3
"""Short 384-resolution adaptation for the mobile PP-MattingV2 graph.

This is intentionally not training from scratch. It loads the official human-512 checkpoint through
our 384 config, freezes STDC1 backbone weights, and updates the DPP/decoder/matting heads on genuine
384x384 PPM-100 crops. That keeps the strong 512 semantic backbone while adapting resolution-
sensitive layers and BN statistics before the fixed-384 export used by Android CI.
"""

import argparse
import os
import random
import sys

import numpy as np
import paddle
import paddleseg
from paddleseg.cvlibs import manager


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--paddleseg_dir", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--save_dir", required=True)
    parser.add_argument("--iters", type=int, default=320)
    parser.add_argument("--batch_size", type=int, default=1)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--seed", type=int, default=20260907)
    return parser.parse_args()


def main():
    args = parse_args()
    matting_dir = os.path.join(os.path.abspath(args.paddleseg_dir), "Matting")
    if not os.path.isfile(os.path.join(matting_dir, "tools", "train.py")):
        raise SystemExit(f"PaddleSeg Matting checkout not found: {matting_dir}")

    sys.path.insert(0, matting_dir)
    manager.BACKBONES._components_dict.clear()
    manager.TRANSFORMS._components_dict.clear()

    import ppmatting  # noqa: F401,E402
    from ppmatting.core import train  # noqa: E402
    from ppmatting.utils import Config, MatBuilder  # noqa: E402

    cfg = Config(args.config, iters=args.iters, batch_size=args.batch_size)
    builder = MatBuilder(cfg)
    paddleseg.utils.set_seed(args.seed)
    paddleseg.utils.set_device(args.device)
    paddle.seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)

    model = paddleseg.utils.convert_sync_batchnorm(builder.model, args.device)
    frozen = 0
    total = 0
    for name, parameter in model.named_parameters():
        total += parameter.numel()
        if name.startswith("backbone."):
            parameter.stop_gradient = True
            frozen += parameter.numel()

    print(
        f"384 adaptation: frozen_backbone_params={frozen} total_params={total} "
        f"trainable_params={total - frozen} iters={cfg.iters} batch={cfg.batch_size}"
    )

    train(
        model,
        train_dataset=builder.train_dataset,
        val_dataset=None,
        optimizer=builder.optimizer,
        iters=cfg.iters,
        batch_size=cfg.batch_size,
        num_workers=0,
        use_vdl=False,
        save_interval=cfg.iters,
        log_iters=max(10, min(40, cfg.iters // 8)),
        resume_model=None,
        save_dir=args.save_dir,
        precision="fp32",
    )

    checkpoint = os.path.join(args.save_dir, f"iter_{cfg.iters}", "model.pdparams")
    if not os.path.isfile(checkpoint):
        raise SystemExit(f"Adapted checkpoint was not produced: {checkpoint}")
    print(f"384 adapted checkpoint ready: {checkpoint} ({os.path.getsize(checkpoint)} bytes)")


if __name__ == "__main__":
    main()
