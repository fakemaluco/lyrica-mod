fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rerun-if-changed=lyrica.ico");
        println!("cargo:rerun-if-changed=lyrica.rc");
        embed_resource::compile("lyrica.rc", embed_resource::NONE)
            .manifest_required()
            .expect("could not embed the Lyrica Windows resources");
    }
}
