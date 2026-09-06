#!/usr/bin/env bash
# Run in the owner's terminal; apksigner prompts privately for passwords.
set -euo pipefail
backup_tool_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backup_repo_dir="$(cd -- "$backup_tool_dir/../.." && pwd)"
exec python3 "$backup_tool_dir/sign.py" \
  --jdk /home/mrindeciso/Applications/android-studio/jbr \
  --sdk /home/mrindeciso/Android/Sdk \
  --keystore /home/mrindeciso/Android/keystore.jks \
  --alias key0 \
  --apk "$backup_repo_dir/release/backup-helper/auxio-backup-helper-unsigned.apk" \
  --out "$backup_repo_dir/release/backup-helper/auxio-backup-helper-signed.apk"
