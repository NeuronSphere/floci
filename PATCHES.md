# NeuronSphere patches

This branch is upstream Floci plus the patches below, built as
`ghcr.io/hmdlabs/floci:<base>-ns.<n>` for NeuronSphere local environments
(`nsctl`). Every patch is also proposed upstream. When this table is empty,
nsctl goes back to the stock `floci/floci` image and this fork goes idle.

Base: upstream `main` at `7d9e38cf5` (after 2.1.0, before 2.2.0).

| Patch | Upstream branch | Upstream issue / PR | Status |
|---|---|---|---|
| feat(eks): allow-listed host volumes for k3s cluster containers | `feature/eks-host-volumes` | not yet filed | carried |

## Branches

- `main`: mirrors `floci-io/floci` main. Nothing of ours is committed there.
- `feature/<topic>`: one per upstream PR, cut from upstream `main`.
- `ns/<base>`: the upstream base plus the open patches above. Images are built
  only from these branches.

## On each upstream release

1. Cut `ns/<version>` from the release tag.
2. Cherry-pick each patch still marked `carried`. Floci squash-merges, so check
   the upstream PR status by hand; patch-id matching will not find merged work.
3. Build, push `ghcr.io/hmdlabs/floci:<version>-ns.<n>`, run the nsctl e2e, then
   bump the pin in hmd-cli-neuronsphere.

## Building the image

The upstream release images are GraalVM native builds. This fork builds the
JVM image from `docker/Dockerfile`, which needs no native toolchain. It
behaves the same, starts slower and uses more memory.

    docker buildx build --platform linux/amd64,linux/arm64 \
      -f docker/Dockerfile --build-arg VERSION=<base>-ns.<n> \
      -t ghcr.io/hmdlabs/floci:<base>-ns.<n> --push .
