#!/bin/sh
# Run from the repository root: sh tests/gitops_push_retry_test.sh
#
# Exercises the real push block out of vars/buildAndDeployApp.groovy against
# local repositories, so the thing under test is the script Jenkins runs and
# not a copy of it that can drift.
#
# What it pins down is the failure that produced BinanceBot#163: the build's
# image reached the registry, the manifest push lost a race with another job
# on the shared branch, and the build died with no manifest pointing at the
# image it had just built.
set -eu

root=$(pwd)
[ -f vars/buildAndDeployApp.groovy ] || { echo "run me from the repository root" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
block="$work/push_block.sh"

# Render the sh block the way Groovy will: the branch interpolates, the
# escaped shell variables do not.
python3 - "$root/vars/buildAndDeployApp.groovy" "$block" <<'PY'
import sys
src, out = sys.argv[1], sys.argv[2]
s = open(src).read()
marker = '                                    sh """\n'
start = s.index(marker, s.index('git push origin HEAD:') - 4000)
end = s.index('                                    """\n', start)
body = s[start + len(marker):end]
assert 'git rebase origin/' in body, 'push block does not replay onto the branch tip'
open(out, 'w').write(body.replace('${config.k8sConfigsBranch}', 'develop').replace('\\$', '$'))
PY

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@localhost
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@localhost

cd "$work"
git init -q --bare origin.git
git init -q seed
cd seed
git checkout -qb develop
mkdir -p apps/ours apps/theirs
echo "newTag: 'v1'" > apps/ours/kustomization.yaml
echo "newTag: 'v1'" > apps/theirs/kustomization.yaml
git add -A
git commit -qm base
git push -q "$work/origin.git" develop
cd "$work"

clone() {
    rm -rf "$1"
    git clone -q origin.git "$1" 2>/dev/null
    git -C "$1" checkout -q develop
}

remote_tag() { git -C "$work/origin.git" show "develop:apps/$1/kustomization.yaml"; }

# 1. Another job takes the branch between our clone and our push. Ours is
#    replayed on top and both manifests survive.
clone ours
clone theirs
cd theirs
echo "newTag: 'v2'" > apps/theirs/kustomization.yaml
git commit -qam "the other job"
git push -q origin develop
cd ../ours
echo "newTag: 'v2'" > apps/ours/kustomization.yaml
git commit -qam "our build"
sh "$block" >/dev/null 2>&1 || { echo "FAIL: a push that lost the race was not retried" >&2; exit 1; }
cd "$work"
[ "$(remote_tag ours)" = "newTag: 'v2'" ] || { echo "FAIL: our tag did not land" >&2; exit 1; }
[ "$(remote_tag theirs)" = "newTag: 'v2'" ] || { echo "FAIL: the other job's tag was lost" >&2; exit 1; }

# 2. Two builds of the same app race. The one that lost must not put its
#    older tag back, so the rebase conflict is a failure, not a resolution.
clone loser
clone winner
cd winner
echo "newTag: 'v4'" > apps/ours/kustomization.yaml
git commit -qam "newer build"
git push -q origin develop
cd ../loser
echo "newTag: 'v3'" > apps/ours/kustomization.yaml
git commit -qam "older build"
if sh "$block" >/dev/null 2>&1; then
    echo "FAIL: an older build overwrote a newer one" >&2
    exit 1
fi
cd "$work"
[ "$(remote_tag ours)" = "newTag: 'v4'" ] || { echo "FAIL: the newer tag was lost to the older build" >&2; exit 1; }

# 3. Nobody else is pushing: one attempt, no replay.
clone alone
cd alone
echo "newTag: 'v5'" > apps/ours/kustomization.yaml
git commit -qam "uncontested build"
if sh "$block" 2>&1 | grep -q 'replaying onto'; then
    echo "FAIL: an uncontested push went round the retry loop" >&2
    exit 1
fi
cd "$work"
[ "$(remote_tag ours)" = "newTag: 'v5'" ] || { echo "FAIL: the uncontested tag did not land" >&2; exit 1; }

echo "PASS: contended push replays and lands, same-app race refuses, uncontested push goes straight through"
