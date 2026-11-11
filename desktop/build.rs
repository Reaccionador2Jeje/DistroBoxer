use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let source = manifest_dir.join("icons/distroboxer.svg");
    let png_destination = manifest_dir.join("icons/icon.png");
    let ico_destination = manifest_dir.join("icons/icon.ico");
    println!("cargo:rerun-if-changed={}", source.display());

    let svg = std::fs::read(&source).expect("read DistroBoxer SVG icon");
    let tree = resvg::usvg::Tree::from_data(&svg, &resvg::usvg::Options::default())
        .expect("parse DistroBoxer SVG icon");
    let mut pixmap = tiny_skia::Pixmap::new(256, 256).expect("create icon pixmap");
    let source_size = tree.size();
    let scale = (256.0 / source_size.width()).min(256.0 / source_size.height());
    let transform = tiny_skia::Transform::from_scale(scale, scale);
    resvg::render(&tree, transform, &mut pixmap.as_mut());
    pixmap
        .save_png(&png_destination)
        .expect("write generated desktop PNG icon");

    // A single-image ICO container whose image payload is the generated PNG.
    let png = std::fs::read(&png_destination).expect("read generated PNG icon");
    let mut ico = Vec::with_capacity(22 + png.len());
    ico.extend_from_slice(&0_u16.to_le_bytes());
    ico.extend_from_slice(&1_u16.to_le_bytes());
    ico.extend_from_slice(&1_u16.to_le_bytes());
    ico.extend_from_slice(&[0, 0, 0, 0]);
    ico.extend_from_slice(&1_u16.to_le_bytes());
    ico.extend_from_slice(&32_u16.to_le_bytes());
    ico.extend_from_slice(&(png.len() as u32).to_le_bytes());
    ico.extend_from_slice(&22_u32.to_le_bytes());
    ico.extend_from_slice(&png);
    std::fs::write(&ico_destination, ico).expect("write generated Windows ICO icon");

    tauri_build::build()
}
