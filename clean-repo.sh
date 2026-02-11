#!/bin/bash
# Clean a GitHub repo to an empty main branch
# Usage: ./clean-repo.sh <owner/repo>
# Example: ./clean-repo.sh dbbaskette/wm-test

set -e

REPO="$1"

if [ -z "$REPO" ]; then
  echo "Usage: $0 <owner/repo>"
  exit 1
fi

echo "Cleaning repo: $REPO"

# Get all branches
BRANCHES=$(gh api "repos/$REPO/branches" --jq '.[].name' 2>&1)
echo "Current branches: $BRANCHES"

# Create a temp dir, push an empty main branch
TMPDIR=$(mktemp -d)
cd "$TMPDIR"
git init -q
git checkout -b main
git commit --allow-empty -m "Initial empty commit" -q
git remote add origin "https://github.com/$REPO.git"
git push --force origin main 2>&1

# Set main as default branch
gh api -X PATCH "repos/$REPO" -f default_branch=main --silent

# Delete all other branches
for BRANCH in $BRANCHES; do
  if [ "$BRANCH" != "main" ]; then
    REF="heads/$(echo "$BRANCH" | sed 's/ /%20/g')"
    echo "Deleting branch: $BRANCH"
    gh api -X DELETE "repos/$REPO/git/refs/$REF" 2>&1 || true
  fi
done

# Cleanup
rm -rf "$TMPDIR"

echo "Done. $REPO is clean with only an empty main branch."
