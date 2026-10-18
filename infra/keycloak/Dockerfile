# The fleet's identity server (#325): stock Keycloak plus the common-password list
# every realm's policy refuses (passwordBlacklist). Built from the compose file
# like every other image, so CI builds, scans, signs and attests it.
FROM quay.io/keycloak/keycloak:26.3
COPY security/blocklist/ /tmp/blocklist/
# one file, because a policy names one: the NCSC list as published, then our brand words.
# Keycloak reads every *.txt in data/password-blacklists into a Bloom filter at first use.
RUN mkdir -p /opt/keycloak/data/password-blacklists \
 && cat /tmp/blocklist/ncsc-100k.txt /tmp/blocklist/brand-words.txt \
      > /opt/keycloak/data/password-blacklists/common-passwords.txt
