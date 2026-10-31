#!/usr/bin/env bash
# NiuBackup M4b deployment on the cloud server (36.133.114.164).
# Reference script: these are exactly the steps that were executed for the
# initial deployment (2026-10-31). Everything lives under /opt/niubackup and
# is separable from the server's other workloads. Run as root on the server.
set -euo pipefail

# 0. Private JDK 21 (server ships only JDK 11/17). Huawei OpenJDK mirror is
#    reachable through the server's mihomo proxy.
mkdir -p /opt/niubackup/build
cd /opt/niubackup
if [ ! -x jdk21/bin/java ]; then
  curl -s -x http://127.0.0.1:7890 -o jdk21.tar.gz \
    https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_linux-x64_bin.tar.gz
  mkdir -p jdk21-tmp && tar xzf jdk21.tar.gz -C jdk21-tmp
  mv jdk21-tmp/jdk-21.0.2 jdk21 && rmdir jdk21-tmp && rm jdk21.tar.gz
fi

# 1. PostgreSQL: dedicated role + database "niubackup".
#    The generated password is written ONLY to /opt/niubackup/server.env (600).
if ! su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='niubackup'\"" | grep -q 1; then
  DBPW=$(openssl rand -hex 24)
  printf "CREATE ROLE niubackup LOGIN PASSWORD '%s';\nCREATE DATABASE niubackup OWNER niubackup;\n" "$DBPW" \
    | su postgres -c "psql -q"
  cat > /opt/niubackup/server.env <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/niubackup
DB_USER=niubackup
DB_PASS=$DBPW
NB_PORT=18090
NB_MESH_ADDR=10.200.0.1
NB_PUBLIC_NODE=tcp://36.133.114.164:11011
ENV
  chmod 600 /opt/niubackup/server.env
  unset DBPW
fi

# 2. Build on the server (Maven + Aliyun mirror via the local proxy), because
#    the build VM's egress proxy breaks Java TLS (see docs/m4b-notes.md).
cp -r server /opt/niubackup/build/
cp deploy/maven-settings.xml /opt/niubackup/build/settings.xml
cd /opt/niubackup/build/server
JAVA_HOME=/opt/niubackup/jdk21 mvn -q -s /opt/niubackup/build/settings.xml -DskipTests package
cp target/niubackup-server.jar /opt/niubackup/niubackup-server.jar

# 3. Services.
cp deploy/niubackup-server.service /etc/systemd/system/
cp deploy/easytier-public.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now niubackup-server
systemctl enable --now easytier-public

# 4. Firewall (append-only, persisted the same way as existing rules):
#    11011 tcp/udp for the public mesh node; 18090 only via the mesh tun.
iptables -C INPUT -p tcp --dport 11011 -j ACCEPT 2>/dev/null || iptables -A INPUT -p tcp -m tcp --dport 11011 -j ACCEPT
iptables -C INPUT -p udp --dport 11011 -j ACCEPT 2>/dev/null || iptables -A INPUT -p udp -m udp --dport 11011 -j ACCEPT
iptables -C INPUT -i tun0 -p tcp --dport 18090 -j ACCEPT 2>/dev/null || iptables -A INPUT -i tun0 -p tcp -m tcp --dport 18090 -j ACCEPT
iptables-save > /etc/iptables/rules.v4

# 5. Seed the first admin (once): generate a password, BCrypt it with the
#    spring-security-crypto jar from the local Maven repo, insert the hash.
#    Hand the plaintext to the operator once; store nothing but the hash.
