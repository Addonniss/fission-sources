# Security policy

## Reporting a vulnerability

**Do not report a vulnerability in a public Issue, discussion, pull request or comment.**
A public report tells everyone how to abuse the problem before a fix exists, and it cannot
be withdrawn.

Report privately, through GitHub's **private vulnerability reporting** on the public
releases repository:

**<https://github.com/Addonniss/fission-releases/security/advisories/new>**

Private vulnerability reporting is enabled on `Addonniss/fission-releases` — the release
feed that carries v0.1.4 and every later build — so the form is available there. Use this
link rather than browsing for a form elsewhere: an advisory form on a repository where
private reporting is not enabled cannot accept a report, and this is the only channel that
does.

Reports about older builds are welcome through the same form. v0.1.0 to v0.1.4 were
published to `Addonniss/infusion-releases`, which is kept as the legacy compatibility feed
for those releases; it is no longer the reporting destination, and nothing about those
builds is out of scope because of where they were downloaded from.

### If the private form is not available

If that page does not offer a private report form (for example if private reporting is ever
disabled on the repository), open an Issue in
<https://github.com/Addonniss/fission-releases> containing **only** the sentence
"security report: please open a private channel" — no technical detail, no reproduction
steps, no affected version — and wait to be contacted. The details belong in the private
channel, not in the Issue.

There is no security email address. Please do not guess one, and do not send reports to
any address you find elsewhere.

## What to include in a private report

- the fission version (`versionName`, `versionCode`) and the device or Android version;
- what the problem allows an attacker to do, and what they need in order to do it;
- the shortest reproduction you can give;
- any evidence (logs, captures) — with credentials, tokens, server addresses, add-on URLs
  and personal media titles removed.

## Scope

In scope:

- the Android TV application (`dev.infusion.tv`) as distributed from
  <https://github.com/Addonniss/fission-releases> and from the legacy compatibility feed at
  <https://github.com/Addonniss/infusion-releases>;
- its handling of credentials, tokens and configured add-on or server URLs;
- its network transport and destination validation;
- the update check and the download of a release asset.

Out of scope:

- the Jellyfin server, the add-ons, the metadata services and the tracking services
  themselves — report those to their own projects;
- vulnerabilities that require a rooted device, a modified system image, or physical
  access to an unlocked device, unless the specific claim is that fission stores
  something it should not;
- reports produced only by an automated scanner with no demonstrated impact;
- social engineering, denial of service by sheer volume, and spam.

## What to expect

fission is an independent personal project maintained by one person. Reports are read,
and genuine security problems are fixed before they are discussed in public, but no
response time or fix deadline is promised. If a report is valid you will be credited in the
release notes for the fix unless you ask not to be.

## Supported versions

Only the most recent public release is supported. Pre-release ("alpha") builds are
supported on a best-effort basis: a report against an older alpha build is welcome, but the
fix will normally land in the next release rather than in a patch for the old one.
