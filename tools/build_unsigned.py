"""Build this explicit source candidate with existing local Android/JDK tools.

No downloads, signing, credentials, device access, or recursive cleanup.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
JAVA_NAMES = ("BuiltinWallpaperActivity", "MainActivity", "IconMapping", "IconRegistry", "OfflineGallery", "OfflineRotationPolicy", "QiyiguoLaunchPolicy", "ScenicView", "VerifiedLaunchPolicy", "WallpaperBootReceiver", "WallpaperController", "WallpaperPolicy", "WallpaperSettingsActivity", "WallpaperTls", "WaveView", "WeatherView")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True, type=Path, help="Existing JDK 17 directory")
    parser.add_argument("--android-jar", required=True, type=Path, help="Existing API 22 android.jar")
    parser.add_argument("--build-tools", required=True, type=Path, help="Existing SDK build-tools containing aapt, zipalign and lib/d8.jar")
    args = parser.parse_args()
    args.java_home = args.java_home.expanduser().resolve()
    args.android_jar = args.android_jar.expanduser().resolve()
    args.build_tools = args.build_tools.expanduser().resolve()
    suffix = ".exe" if __import__("os").name == "nt" else ""
    tools = {"javac": args.java_home / "bin" / ("javac" + suffix), "java": args.java_home / "bin" / ("java" + suffix), "aapt": args.build_tools / ("aapt" + suffix), "zipalign": args.build_tools / ("zipalign" + suffix), "d8": args.build_tools / "lib/d8.jar", "android.jar": args.android_jar}
    for name, path in tools.items():
        if not path.is_file():
            parser.error("Missing existing tool: " + name)
    src = ROOT / "app/src/local/tv/bluehome"
    selected = [src / (name + ".java") for name in JAVA_NAMES]
    if {path.name for path in src.glob("*.java")} != {path.name for path in selected}:
        parser.error("Java file set differs from reviewed 16-file allowlist")
    build_root = ROOT / "build"
    build_root.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="unsigned-", dir=build_root)).resolve()
    if not work.is_relative_to(ROOT.resolve()):
        raise SystemExit("Build output escaped candidate directory")
    generated, classes, dex, resources, assets = [work / name for name in ("generated", "classes", "dex", "res", "assets")]
    for directory in (generated, classes, dex, resources, assets):
        directory.mkdir()
    # Only named resources enter the APK. Local/private config and added assets
    # are never gathered implicitly, even when they exist beside these files.
    for relative in ("drawable/icon.xml", "values/strings.xml"):
        target = resources / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "app/res" / relative, target)
    shutil.copyfile(ROOT / "app/assets/offline-gallery.json", assets / "offline-gallery.json")
    builtin = ROOT / "app/assets/builtin/hidden-lake-cc0.jpg"
    if hashlib.sha256(builtin.read_bytes()).hexdigest() != "ed6f4b7dde0486b99226ade2db1f2412ca806471a5a853d2e00e7a2d6bf0c982":
        raise SystemExit("Reviewed metadata-clean CC0 image hash mismatch")
    (assets / "builtin").mkdir()
    shutil.copyfile(builtin, assets / "builtin/hidden-lake-cc0.jpg")
    # Explicitly reviewed system/tool icons only; private brand adaptations excluded.
    icon_names = ('account', 'box', 'cable', 'cast', 'clock', 'generic', 'hdmi', 'library', 'media', 'message', 'music', 'search', 'settings', 'shield', 'stock', 'store', 'upgrade', 'weather')
    (assets / "icons-white").mkdir()
    for name in icon_names:
        shutil.copyfile(ROOT / "app/assets/icons-white" / (name+".png"), assets / "icons-white" / (name+".png"))
    log_path = work / "local-build.log"
    with log_path.open("w", encoding="utf-8") as log:
        def run(label, command):
            log.write("\n[" + label + "]\n")
            log.flush()
            result = subprocess.run([str(value) for value in command], cwd=work, stdout=log, stderr=subprocess.STDOUT, check=False)
            if result.returncode:
                raise SystemExit(label + " failed; inspect the local build log (not for publication).")
        base = work / "resources.apk"
        run("resources", [tools["aapt"], "package", "-f", "-m", "-M", ROOT / "app/AndroidManifest.xml", "-S", resources, "-A", assets, "-I", tools["android.jar"], "-J", generated, "-F", base])
        java_sources = selected + sorted(generated.rglob("*.java"))
        run("javac", [tools["javac"], "-proc:none", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-implicit:none", "-bootclasspath", tools["android.jar"], "-d", classes, *java_sources])
        jar = work / "classes.jar"
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
            for class_file in sorted(classes.rglob("*.class")):
                archive.write(class_file, class_file.relative_to(classes).as_posix())
        run("d8", [tools["java"], "-cp", tools["d8"], "com.android.tools.r8.D8", "--release", "--min-api", "21", "--lib", tools["android.jar"], "--output", dex, jar])
        unaligned = work / "bluehome-unsigned-unaligned.apk"
        shutil.copyfile(base, unaligned)
        with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as archive:
            dex_files = sorted(dex.glob("classes*.dex"))
            if not dex_files:
                raise SystemExit("D8 did not produce dex output")
            for dex_file in dex_files:
                archive.write(dex_file, dex_file.name)
        output = work / "bluehome-3.3.4-source-candidate-unsigned.apk"
        run("zipalign", [tools["zipalign"], "-f", "4", unaligned, output])
        run("alignment-check", [tools["zipalign"], "-c", "4", output])
        run("package-check", [tools["aapt"], "dump", "badging", output])
    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        if any(name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC")) for name in names):
            raise SystemExit("Unexpected signing material in unsigned output")
        bundled_assets = sorted(name for name in names if name.startswith("assets/"))
        if bundled_assets != sorted(["assets/builtin/hidden-lake-cc0.jpg", "assets/offline-gallery.json"] + ["assets/icons-white/"+name+".png" for name in icon_names]):
            raise SystemExit("Unexpected bundled asset")
    info = {"created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "unsigned": True, "installed": False, "device_tested": False, "java_source_count": 16, "min_api": 21, "target_api": 22, "bundled_assets": bundled_assets, "apk_sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "apk_size_bytes": output.stat().st_size, "tool_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in tools.items()}}
    (work / "build-info.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "unsigned_build_complete", "output_relative": output.relative_to(ROOT).as_posix(), "apk_sha256": info["apk_sha256"], "java_source_count": 16, "bundled_assets": bundled_assets, "installed": False}))


if __name__ == "__main__":
    main()
