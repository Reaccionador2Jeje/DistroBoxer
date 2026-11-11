use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let source = manifest_dir.join("icons/distroboxer.svg");
    let destination = manifest_dir.join("icons/icon.png");
    println!("cargo:rerun-if-changed={}", source.display());

    let svg = std::fs::read(&source).expect("read DistroBoxer SVG icon");
    let tree = resvg::usvg::Tree::from_data(&svg, &resvg::usvg::Options::default())
        .expect("parse DistroBoxer SVG icon");
    let mut pixmap = tiny_skia::Pixmap::new(512, 512).expect("create icon pixmap");
    let source_size = tree.size();
    let scale = (512.0 / source_size.width()).min(512.0 / source_size.height());
    let transform = tiny_skia::Transform::from_scale(scale, scale);
    resvg::render(&tree, transform, &mut pixmap.as_mut());
    pixmap.save_png(&destination).expect("write generated desktop icon");

    tauri_build::build()
}
