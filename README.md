<p align="center"><img src="src/main/resources/META-INF/pluginIcon.svg" width="96" alt="perINKpheral"></p>

# perINKpheral for CLion

CLion plugin for developing [perINKpheral](https://perinkpheral.dev) applications.
Includes toolchain setup, application builds, device access and a simulator.

[Plugin documentation](https://perinkpheral.dev/sdk#clion) -
[Developer documentation](https://perinkpheral.dev/sdk).
Application guide (PDF):
[English](https://github.com/Perinkpheral/perinkpheral-os/releases/latest/download/perinkpheral-app-guide.pdf),
[Russian](https://github.com/Perinkpheral/perinkpheral-os/releases/latest/download/perinkpheral-app-guide.ru.pdf).

## Requirements

CLion 2026.2 or later. Set Up Toolchain downloads the SDK and wasi-sdk;
git must be on `PATH`. Screen capture, button injection and development uploads
require firmware with `APP_DEV_CONSOLE` enabled. Status, logs and firmware updates
also work with release firmware. The bundled simulator supports macOS and Linux.
Update an existing SDK checkout before using this plugin. Generated headers are
stored separately for each application checkout.

USB firmware uploads check the board's partition table before writing. For an old
two-slot board, first choose Keep data in the [web flasher](https://perinkpheral.dev/flash)
to apply the release's data-preserving migration manifest. After a USB firmware
upload, press BACK (Exit recovery) on the device to validate and start the image.

## Setup and run

Run Tools - perINKpheral - Set Up Toolchain. Set Device address under
Settings - Tools - perINKpheral. Open an application folder and accept CMake
setup or replacement when offered. Select its device or simulator run
configuration, then run it. Upload to Device is also available from the project
and editor context menus.

## Build

Requires Java 21 and a sibling `../perinkpheral-os` checkout for `tools/core`.
`-PperinkpheralPath=<checkout>` selects another checkout;
`-PclionPath=<installation>` selects an installed CLion.

```sh
./gradlew buildPlugin test
```

The plugin archive is written to `build/distributions/`.
Installation: Settings - Plugins - Install Plugin from Disk.

Bundled simulators record their source inputs in
`src/main/resources/simulator/build.properties`. The test hashes sorted relative
paths and raw contents of `tools/simulator/Makefile`, `sim.c`, every firmware
and SDK source compiled by that Makefile, included local files, wasm3 and font
header prerequisites, and the SDK headers, Kotlin import generators and CLI page
used to generate embedded data. Each path and content is prefixed by its 8-byte
big-endian byte length. Per-file hashes identify changed,
added or removed inputs; each binary also retains its own SHA-256. Unrelated
firmware commits require no rebuild.

When these inputs change, build all three platforms using
`tools/simulator/Makefile` in the firmware checkout and copy the executables into
the corresponding resource folders. Immediately after building and copying the
binaries, run `./gradlew recordSimulatorProvenance -PperinkpheralPath=<checkout>`
before making any further source changes. Then run
`./gradlew test -PperinkpheralPath=<checkout>` with the same checkout. Keep the
source and include lists in `SimulatorSourceProvenance.kt` in sync when the
Makefile changes; the Makefile itself is hashed so such changes are detected.

When upgrading from the old `org.perinkpheral.clion` plugin, uninstall it before
installing this `dev.perinkpheral.clion` build; the IDE treats the IDs as separate plugins.

CI builds and tests every push. Each successful push to `main` releases the plugin archive.

## Related sources

- [Firmware, SDK and shared Kotlin core](https://github.com/Perinkpheral/perinkpheral-os):
  the Perinkpheral OS contract, application builder and device client used by this plugin.
- [Application examples and tests](https://github.com/Perinkpheral/perinkpheral-apps).
- [Portal source](https://github.com/Perinkpheral/perinkpheral-dev-portal): the public
  SDK pages linked above. Keep shared tool behavior in `tools/core`, not a second
  implementation in the plugin.

## Repository

- `src/main/kotlin/dev/perinkpheral/clion/` - IDE actions, settings, run configurations and device tools.
- `src/main/resources/simulator/` - bundled simulator binaries.
- `src/main/resources/schemas/` - application manifest schema.
- `gradle.properties` - plugin version and CLion compatibility.

## License

[MIT](LICENSE).
