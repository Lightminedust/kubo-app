# Publishing Kubo to winget

`winget install Gemmie.Kubo` works because a manifest describing Kubo lives in Microsoft's public
index, [microsoft/winget-pkgs]. Getting it there is a pull request, reviewed by Microsoft, that has to
pass an automated pipeline: the manifest is checked against the schema, the installer is downloaded and
its hash compared, and it is installed and uninstalled inside a clean virtual machine.

Nothing here is automatic. Each new version of Kubo needs its own pull request.

## Why the MSI and not the .exe

The website offers `Kubo-Setup.exe`, because that is what a person double-clicks. winget gets
`Kubo-0.3.0.msi` instead, built from exactly the same application image.

An MSI installs and uninstalls silently by definition, and carries its own ProductCode and UpgradeCode,
so winget can tell an installed Kubo apart from anything else and knows when an upgrade replaces it.
A jpackage `.exe` gives none of that for free.

Both installers are built from the same `jpackageCommand` in `app/build.gradle.kts`, so they cannot
drift apart.

## Steps for a release

Everything below assumes the version is already released on GitHub, with `Kubo-<version>.msi` attached.

**1 — Build the MSI, stamped with the release version.**

```
gradlew jpackage -PinstallerType=msi -PappVersion=0.3.0
```

Needs the WiX Toolset v3 on the PATH. It lands in `app\build\jpackage\Kubo-0.3.0.msi`.

**2 — Attach that exact file to the GitHub release.** The hash in the manifest is the hash of the file
you are about to generate from, so it must be the same bytes people will download. Build once, upload
that file, then carry on.

**3 — Generate the manifests.**

```
powershell -ExecutionPolicy Bypass -File packaging\winget\build-manifests.ps1 `
    -Msi app\build\jpackage\Kubo-0.3.0.msi
```

This reads the SHA256 and the MSI's ProductCode — a fresh GUID on every build, which is why the
manifests are generated rather than edited — and writes three files into
`packaging\winget\manifests\0.3.0\`.

**4 — Check them locally before asking anyone to review them.**

```
winget validate --manifest packaging\winget\manifests\0.3.0
winget install --manifest packaging\winget\manifests\0.3.0
```

The second one really installs Kubo from the manifest. Do it on a machine you do not mind installing
on, and uninstall afterwards.

**5 — Open the pull request.** Fork [microsoft/winget-pkgs], copy the generated folder to

```
manifests\g\Gemmie\Kubo\0.3.0\
```

and open a PR from a branch of your fork. The path is derived from the package identifier
(`Gemmie.Kubo`), and the lower-case first letter of the publisher is part of it.

`wingetcreate update Gemmie.Kubo --version 0.3.0 --urls <msi url>` can do steps 3 and 5 for you once
the package exists in the index; it computes the hash and opens the PR itself. The first submission is
easier to do by hand, because there is nothing to update yet.

## What the review checks

- The schema, strictly. A wrong indent or an unknown field fails.
- That the installer downloads from the declared URL and matches the declared SHA256.
- That it installs and uninstalls silently in a sandbox.
- That a virus scan comes back clean.

Kubo is **not code-signed**, which is allowed — plenty of packages in the index are not. It means the
sandbox may flag SmartScreen behaviour, and a human reviewer may ask about it. Signing remains the one
thing that would make every warning go away, here and on the website both.

## After it is merged

Set `WINGET_ID` in `site/index.html` to `Gemmie.Kubo`. The winget card on the download page is hidden
while that value is empty, so the page never shows a command that does not work yet.

[microsoft/winget-pkgs]: https://github.com/microsoft/winget-pkgs
