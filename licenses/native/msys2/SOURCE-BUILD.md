# Bundled SVN CLI: source and rebuilding

The Windows release contains `svn.exe`, `svnmucc.exe`, and their
required DLLs from the official MSYS2 packages listed in `../dependencies.json`.
The Serf DLL is rebuilt with the enclosed empty-peek patch; the other files
are unchanged. It does not require an installed SVN client. The application invokes the CLI
as a separate process. Each component retains its own license.

`packages/<name>/PKGINFO` identifies the exact binary package.
`BUILDINFO` records its compiler/build environment and the original recipe hash.
`PKGBUILD` and `.SRCINFO` are the original recipes extracted from the matching
official source package. Source archives include the upstream sources, recipe,
patches, and auxiliary input files, rather than only links to upstream sources.
Original license and notice texts are retained in each package directory and
inside its complete source archive. The manifest records SHA-256 hashes for
every distributed binary, legal file, source archive, and source fragment.

## Restore or check the binaries

From the project root, on Windows with Python 3.8+ and Windows `tar.exe`:

```powershell
python scripts/fetch-svn-cli.py
python scripts/fetch-svn-cli.py --verify
```

There are no Python package dependencies. Downloads use Python's standard
proxy settings and pinned public MSYS2 URLs. Runtime operation is offline with
respect to downloading/installing SVN. Existing source/legal material must be
present when verifying the complete distribution.

## Rebuild a library or CLI

Use a separate MSYS2 **MSYS** build environment, with `base-devel` and the
`makedepends` specified in that package's `PKGBUILD`. `BUILDINFO` records the
exact original dependency versions for reproduction. These build tools are
not required to run ProbeLoom. For a normal source archive:

```sh
mkdir source-build && cd source-build
tar -xf /path/to/component-version.src.tar.zst
cd component
makepkg --verifysource
makepkg --syncdeps
```

All declared upstream source inputs and local patches are included, so
`makepkg` can consume them locally. Installing the build toolchain/dependencies
can require access to the package repositories. `makepkg --syncdeps` is the
normal build entry point; it is not a claim that a fresh machine already has
the build dependencies installed.

The complete GCC and OpenSSL source packages are split into three and two
files respectively, each at most 40 MiB. Reassemble and verify them before
extracting:

```powershell
python licenses/native/msys2/reassemble-source.py .build/reassembled-sources
```

Each resulting archive is byte-for-byte identical to its official source
package. The manifest records each total hash and every fragment hash.

The runtime's original official source archive contains Git repository
metadata. Its corresponding-source archive instead contains the complete
`cygwin-3.6.12` source tree exported from the exact recorded commit, all original
47 MSYS2 patches, the commit-identification file, original build recipe, and
`PKGBUILD.offline`. Extract it and use the supplied local-source recipe:

```sh
tar -xf msys2-runtime-3.6.12-1-corresponding.tar.gz
cd msys2-runtime
makepkg -p PKGBUILD.offline --verifysource
makepkg -p PKGBUILD.offline --syncdeps
```

`PKGBUILD.offline` replaces the Git source input with the enclosed exact source
archive and removes the checkout-only Git setting check. It preserves the
original patch sequence and compilation/package steps. Upstream Git source
URL, tag, resolved commit, original source-package hash, exported source hash,
and derived source-package hash are recorded in the manifest. No upstream
source code has been modified by ProbeLoom.

## Replacing the shared libraries

The distributed libraries are ordinary separate DLLs. Users may rebuild and
replace them under `native/windows-x86_64/bin`, preserving the binary interfaces
expected by the other bundled components. Modified versions may be used;
reverse engineering for debugging those modifications is permitted. Run
ProbeLoom with the modified DLLs directly; the hash-check command is an audit
of the original distribution and does not prevent runtime replacement.

Keep the corresponding-source material and original license/notice texts
with any binary redistribution. Preserve modified-source notices when making
changes. The application Apache-2.0 license does not replace the separate
component licenses. The runtime LGPL-3.0-or-later linking exception and GCC
Runtime Library Exception are included verbatim with their respective source
notices. The source package also contains licenses for tools/docs which are
not themselves included as executables in the runtime bundle.

## Rebuild the patched Serf DLL

`build/serf-empty-peek.patch` corrects the read-side contract for a successful
peek returning zero bytes. The underlying bucket maps EAGAIN to success with
zero length; treating it as a server response invoked an uninitialized response
acceptor when the first resolved address refused a connection. The change
returns to polling for that exact no-data state, preserving EOF/error handling
and allowing the normal address fallback. It contains no diagnostic logging.

Run from the project root on Windows with Python 3.8+ and `tar.exe`:

```powershell
python scripts/build-svn-serf.py
```

The script downloads a pinned, SHA-256 checked open-source MSYS2 build
toolchain listed in `build/tool-packages.json` into `.build/`, extracts the
complete enclosed Serf source package, applies its five original MSYS2 patches
and the enclosed fix, builds using SCons/GCC, strips debug data, and installs
the resulting DLL. No Microsoft compiler or proprietary SDK is used.
The toolchain packages are build-only downloads and are not redistributed
with the application. The original and modified source is fully supplied.

`fetch-svn-cli.py` restores the official unmodified selected files and verifies
the supplied patched DLL; it never overwrites that DLL with the upstream
unpatched file. Rebuilding can change PE timestamps/build metadata, so the
manifest's hash identifies the distributed build rather than requiring all
independent builds to be byte-identical. The built DLL retains the same runtime
imports as the official package.
