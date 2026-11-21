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

## Build

Requires Java 21 and a sibling `../perinkpheral-os` checkout for `tools/core`.
`-PperinkpheralPath=<checkout>` selects another checkout;
`-PclionPath=<installation>` selects an installed CLion.

```sh
./gradlew buildPlugin test
```

The plugin archive is written to `build/distributions/`.
Installation: Settings - Plugins - Install Plugin from Disk.

Bundled simulators must be built from that firmware checkout's commit. The test
compares `src/main/resources/simulator/build.properties` with the checkout's HEAD
and checks each binary's SHA-256. CI selects firmware `main`, so a firmware update
requires rebuilding the bundles before the next plugin release. Build all three
platforms using `tools/simulator/Makefile` in the firmware checkout, copy the
executables into the corresponding resource folders, and record the full commit
and new hashes in `build.properties`.

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
