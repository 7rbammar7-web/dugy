#!/bin/bash
# تثبيت خادم DUGY على Raspberry Pi (أو أي جهاز Debian).
# الاستخدام:  sudo bash dugy-install.sh
#
# ما يفعله:
#  1) يحدّث النظام ويفعّل التحديثات الأمنية التلقائية.
#  2) ينشئ مستخدمًا خاصًا بلا صلاحيات اسمه dugy ويشغّل الخادم به.
#  3) الخادم يستمع على 127.0.0.1 فقط، فلا يصل إليه أحد من الشبكة مباشرة.
#  4) يثبّت Tailscale ويجعل الخادم متاحًا داخل شبكتك الخاصة فقط (tailscale serve، وليس funnel).
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "شغّل الأمر هكذا:  sudo bash $0"
    exit 1
fi

say() { printf '\n==> %s\n' "$*"; }

say "تحديث النظام (قد يأخذ عدة دقائق)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get -y -q upgrade
apt-get install -y -q python3 python3-aiohttp unattended-upgrades curl

cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

say "إنشاء مستخدم الخدمة..."
if ! id dugy >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/dugy --shell /usr/sbin/nologin dugy
fi
install -d -o dugy -g dugy -m 700 /var/lib/dugy
install -d -o root -g root -m 755 /opt/dugy

say "نسخ الخادم..."
cat > /opt/dugy/dugy_server.py <<'DUGY_SERVER_PY'
@@DUGY_SERVER_PY@@
DUGY_SERVER_PY
chmod 644 /opt/dugy/dugy_server.py

cat > /usr/local/bin/dugy-invite <<'EOF'
#!/bin/bash
# ينشئ رمز دعوة جديدًا لاستخدام واحد خلال 48 ساعة.
exec sudo -u dugy /usr/bin/python3 /opt/dugy/dugy_server.py --db /var/lib/dugy/dugy.db invite
EOF
chmod 755 /usr/local/bin/dugy-invite

cat > /etc/systemd/system/dugy.service <<'EOF'
[Unit]
Description=DUGY relay server
After=network-online.target
Wants=network-online.target

[Service]
User=dugy
Group=dugy
ExecStart=/usr/bin/python3 /opt/dugy/dugy_server.py --db /var/lib/dugy/dugy.db --host 127.0.0.1 --port 8080
Restart=always
RestartSec=3

# تقييد صلاحيات الخدمة.
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/dugy
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
LockPersonality=yes
CapabilityBoundingSet=
AmbientCapabilities=
SystemCallArchitectures=native
UMask=0077

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable dugy >/dev/null
systemctl restart dugy
sleep 2
if ! curl -fsS http://127.0.0.1:8080/v1/health >/dev/null; then
    echo "تعذّر تشغيل الخادم. أرسل ناتج هذا الأمر:  journalctl -u dugy -n 50"
    exit 1
fi
echo "الخادم يعمل."

say "Tailscale..."
if ! command -v tailscale >/dev/null 2>&1; then
    curl -fsSL https://tailscale.com/install.sh | sh
fi
if ! tailscale status >/dev/null 2>&1; then
    echo "سجّل دخول الراسبيري في حسابك على Tailscale: افتح الرابط الذي سيظهر الآن من هاتفك."
    tailscale up
fi

# داخل الشبكة الخاصة فقط، بشهادة HTTPS صالحة من Tailscale.
if ! tailscale serve --bg --https=443 http://127.0.0.1:8080; then
    echo
    echo "لم يُفعَّل HTTPS بعد في شبكتك. من لوحة Tailscale على المتصفح:"
    echo "  DNS  ← فعّل MagicDNS  ثم  HTTPS Certificates ← Enable"
    echo "ثم أعد تشغيل هذا الملف."
    exit 1
fi

name=$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')

say "تم."
echo "عنوان الخادم (أرسله لي لأضعه في التطبيق):"
echo
echo "    https://$name"
echo
echo "لإنشاء رمز دعوة لصديق:   sudo dugy-invite"
echo "لمعرفة حالة الخادم:      systemctl status dugy"
