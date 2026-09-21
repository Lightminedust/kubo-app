# Kubo

Kubo is a Windows desktop app that connects to **your own Linux server over SSH** and lets you see and manage it
visually: live performance, open ports and what is really exposed, files (browse, edit, copy, move, permissions,
upload, download), Docker containers, and the full history of every command it sent.

Nothing is installed on the server. Kubo only runs ordinary shell commands through SSH, and lists every one of them.

---

## Requirements

| Side | What is needed |
| --- | --- |
| **Your PC** | Windows 10 or 11, or an x86-64 Linux desktop with GTK 3. The packaged app brings its own Java; building it needs **JDK 21**. |
| **The server** | **Any mainstream Linux server** — Debian, Ubuntu, RHEL, Rocky, Alma, Fedora, Arch, openSUSE. **Packages** needs apt/dpkg, so it is Debian-family only; every other screen works anywhere. The file manager needs **GNU tools**: coreutils (`stat -c`, `mv -T`, `readlink -f`, `mktemp`, `df -B1`, `du -b`), findutils (`find -printf`), GNU `tar`, `bash`/`sh`, `/proc`, and `ss` (iproute2). Debian, Ubuntu, Fedora, RHEL, Arch… work. |
| **Access** | An SSH account with a password or a private key (PuTTY `.ppk`, OpenSSH, PEM). |

**Not supported as servers:** BusyBox systems (Alpine without coreutils, most routers/NAS), BSD, macOS. Kubo checks
the GNU tools when the file manager opens and warns if some are missing: listings, saves and transfers would fail.

---

## Install it

Every package below is built from the same application image, and each brings its own Java runtime, so there is
nothing to install first.

**Windows 10 / 11**

| | |
| --- | --- |
| **Installer** | [`Kubo-Setup.exe`](https://github.com/Lightminedust/Kubo/releases/latest) — the usual way. Start-menu entry, and a later version replaces this one rather than sitting beside it. |
| **Portable** | `Kubo-windows-portable.zip` — unzip anywhere and run `Kubo.exe`. No administrator, nothing in the registry; delete the folder and it is gone. |
| **winget** | `winget install Gemmie.Kubo` — once Microsoft's index has accepted the package. See [packaging/winget](packaging/winget/README.md). |

Not code-signed yet, so Windows may call the publisher unknown: **More info → Run anyway**.

**Linux desktop** — x86-64, needs a graphical session and GTK 3 (`libgtk-3-0`, which every mainstream desktop
already has). Kubo *manages* Linux servers; it does not run on one.

| | |
| --- | --- |
| **Debian / Ubuntu** | `sudo apt install ./kubo_0.3.0_amd64.deb` |
| **Fedora / RHEL / openSUSE** | `sudo dnf install ./kubo-0.3.0-1.x86_64.rpm` |
| **Portable** | `tar -xzf Kubo-linux-x86_64.tar.gz && ./Kubo/bin/Kubo` |

One difference on Linux: Kubo will not remember a password. Windows can encrypt one for a single account
(DPAPI); Linux has no equivalent every machine is guaranteed to have, so Kubo stores nothing rather than
writing a secret somewhere it could be read. Key files are still remembered by their path.

---

## Run it

```powershell
.\gradlew.bat run                # start Kubo from the sources
.\gradlew.bat jpackage           # a plain folder in build/jpackage, with its own Java runtime
.\gradlew.bat portable           # build/distributions/Kubo-windows-portable.zip
.\gradlew.bat jpackage -PinstallerType=exe   # the installer offered on the site
.\gradlew.bat jpackage -PinstallerType=msi   # the installer winget runs
```

```bash
./gradlew tarball                          # build/distributions/Kubo-linux-x86_64.tar.gz
./gradlew jpackage -PinstallerType=deb      # Debian, Ubuntu and derivatives
./gradlew jpackage -PinstallerType=rpm      # Fedora, RHEL, Rocky, Alma, openSUSE
```

**jpackage cannot build for another system than the one it runs on.** The Windows installers need Windows and
the WiX Toolset v3 on the PATH; the Linux ones need Linux with `fakeroot`, `binutils`, `dpkg-dev` and `rpm`. CI
builds both on a tag. Add `-PappVersion=0.3.0` to stamp a release version.

---

## What it does

| Screen | What you see and do |
| --- | --- |
| **Connection** | Password or private key, host key confirmation on first contact, saved sessions. The illustration is a fall of ones and zeros — the server's violet on one side, this computer's cyan on the other; it quickens while Kubo connects, resolves to one green when the session opens, washes amber while it waits for you to trust a host key, and breaks red on failure. |
| **Packages** | Everything apt and dpkg report, as the same branching shape as **Files**: the server is the root, it branches into what packages are *for* — system, libraries, services, development, applications, docs & data — a branch opens into its dpkg sections, and a section into its packages. One row per thing, so a thousand packages never write over each other. The ring on a dot is the share someone actually asked for, the arc outside it what is waiting (red for security), and a switch hides everything that only came in as a dependency. Drag to pan with inertia, ctrl+wheel to zoom, FIT to frame. Install, remove, upgrade, autoremove and clean, each shown first as an apt simulation and confirmed by typing the name back. A **?** button opens the legend: the six branches, what a dot's ring and arcs mean, and what each action does. |
| **Scope** | The machine and nothing else, with one switch — SERVER | PACKAGES — as the only loud piece of navigation. What it is (OS, kernel, CPU, RAM), what it can do and what is left: the CPU sphere with memory, swap, load and steal read around it, then what is running, the disks, and the session log. What the network exposes is in **Network**. |
| **Files** | Opens straight on your home folder showing folders and files (the top bar switches to folders only); the tree runs from `/` to your home and beyond; right-click for edit, rename, copy, move, permissions (with a lexicon), download, delete; click a folder's space for new file / folder; drag files in to upload; the **⇅ THIS PC** card to browse your computer; a **ROOT** button so the tree can read and write where only root may — editing `/etc` configs, dropping files into `/var/www`; a shell that lists every command. |
| **Editor** | Edits text files up to 512 KiB; refuses to overwrite changes made on the server meanwhile. |
| **Logs** | Every session's commands, filterable by origin (monitor, files, shell, Docker, Redis, network, packages) and failures. |
| **Network** | The way in, as a tree of particles read left to right: the internet on one side, this server on the other, the firewall as a pane of mirror glass between them — whole and lit while it enforces, shattered when nothing filters, fogged when Kubo could not read it, and carrying a cross wherever a branch is turned back — and the ports branched by how far they can really be reached — exposed, open, shielded, private, local. The firewall is whichever one the server runs — **UFW, firewalld, nftables or iptables** — and where its rules are in a shape Kubo does not read, it says so instead of claiming nothing is filtered. Each port is named by cross-referencing `ss` PIDs against PM2 and Docker; exposed databases are flagged; open/close a port (UFW only, the one Kubo can write to), restart, stop or kill what holds it, test and reload Nginx, and a shell onto the server with the live command log beside it. |
| **Docker** | Containers, their state, CPU and memory, ports and volumes, start / stop / restart / delete, logs, a terminal inside a container, and a read-only **FILES** browser that walks the container's own filesystem through `docker exec` and opens small text files. |
| **Redis** | A stack of lit isometric slabs, one per key type, each carrying its keys as points of light joined into a lattice, with a band of light sweeping across it. Full key CRUD — including **editing a hash, list, set or sorted set as JSON**, rewritten in one atomic script that keeps its TTL — **`requirepass` support**, DB0–DB15 switching, JSON import/export, memory profiling, SLOWLOG, bounded live MONITOR, interactive Redis CLI, logs and PowerShell. |
| **PowerShell** | A clearly labelled local Windows console; every entry is isolated, non-interactive and limited to 60 seconds. |

---

## Security model

- **Host keys**: the first connection to a server shows its fingerprint and asks. Trusted keys go to
  `%USERPROFILE%\.kubo\known_hosts`. A server whose key **changed** is refused.
- **Secrets**: passwords, passphrases, sudo passwords and a Redis `requirepass` are held as `char[]` and wiped
  after use. A Redis password travels as the `REDISCLI_AUTH` environment variable of each short-lived `redis-cli`
  process, never as `-a` on its command line where any user could read it with `ps`, and never reaches the log. A password or
  passphrase is saved **only if you tick "remember"**, encrypted with Windows DPAPI for your Windows account.
  A sudo password is never written to disk.
- **PATH**: a non-root SSH session has no `/usr/sbin` or `/sbin` on its PATH, so Kubo puts them back on the
  commands it sends for `ufw`, `nginx`, `ss`, `nft`, `iptables` and `firewall-cmd`. Without that it would report a
  running firewall as "not installed". The prefix is on what is sent, never on what is shown or logged.
- **Commands**: every path and name is quoted before reaching the shell. Commands that need root are fixed templates,
  never built from what you type, and each one asks for your permission first.
- **Without root**: the firewall's rules and `nginx -T` only answer to root, but Kubo still reads what it can without it — whether the firewall is running (`systemctl is-active`, `/etc/ufw/ufw.conf`) and the Nginx server blocks straight from `/etc/nginx`. It then says "running, cannot read its rules" rather than "unknown", and never counts an unread firewall as protection.
- **Root mode**: Kubo never becomes root on its own. The **ROOT** button, in the header of the main screen and of Files, Network, Docker and Redis, asks for it once per session; the sudo
  password is then held in memory and shared by every screen, and the wordmark reads `Kubo_root` in every window
  so it is never a surprise. Leaving root mode, or disconnecting, wipes the password immediately.
- **Transparency**: every command is listed in the shell and written to a JSON log, with whether it ran as root, its
  exit code and its duration. Command **outputs — standard output and standard error — and file contents are never
  logged**, only their size. A failed command's error message is shown on screen while the session runs, and not kept.
- **Safe writes**: uploads, downloads and saves land in a temporary file and take their real name only once complete;
  a cancelled or broken transfer leaves nothing half-written.

### Files Kubo keeps on your PC

| Path | Content |
| --- | --- |
| `%USERPROFILE%\.kubo\known_hosts` | Servers you trusted |
| `%USERPROFILE%\.kubo\sessions.json` | Saved sessions (target, method, key path, optional DPAPI-encrypted secret) |
| `%USERPROFILE%\.kubo\logs\*.json` | One log per session: every command, its origin, exit code, duration |

---

## Development

```powershell
.\gradlew.bat test                 # unit tests
.\gradlew.bat integrationTest      # SSH round trips against a server started inside the tests (Apache MINA SSHD)
.\gradlew.bat check                # both, plus the coverage rule (70 % of lines outside the UI)
.\gradlew.bat jacocoTestReport     # coverage report in app/build/reports/jacoco
.\gradlew.bat previewFiles previewScope previewConnection   # render the screens to PNG in app/build/previews
.\gradlew.bat previewDocker previewRedis previewNetwork previewPackages   # the same for the other screens
```

The screens are verified with the preview tasks: they render every state with sample data, without a server.

### Layout

| Package | Role |
| --- | --- |
| `connection` / `connection.ssh` | SSH transport, host key trust, saved sessions, Windows secret protection |
| `command` | One command and its result; the session history |
| `logs` | Writing and reading session logs |
| `monitor` / `monitor.parse` | Polling performance, ports and disks, and parsing the answers |
| `files` | File commands, uploads and downloads (tar writer and safe extractor) |
| `docker` | Docker detection, containers, stats, actions |
| `packages` | apt and dpkg: what is installed, what is waiting, and the changes, each simulated first |
| `network` | Reading `ss`, PM2, UFW and `nginx -T`, and the engine that cross-references them into one chain |
| `privilege` | Root access through sudo, always after asking |
| `model` | Plain records: server, files, accounts, permissions |
| `ui.*` | JavaFX screens; drawing is on canvases, kept apart from state. `BranchCamera` (inertial pan and zoom) and `Fibers` (the bezier strands) are shared by every branching screen — Files, Packages |

### Continuous integration

`.github/workflows/ci.yml` runs `check` on Windows for every push and pull request, publishes the test and coverage
reports, and builds the packaged app for tags `v*`.
