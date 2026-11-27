variable "VCS_REF" {}
variable "BUILD_DATE" {}
variable "RELEASE_TAG" {
  default = ""
}

group "default" {
  targets = ["api", "worker"]
}

target "_backend" {
  context = "."
  args = {
    VCS_REF    = VCS_REF
    BUILD_DATE = BUILD_DATE
  }
  attest = ["type=provenance,disabled=true", "type=sbom,disabled=true"]
  output = ["type=image,push=${RELEASE_TAG != ""}"]
  cache-from = [
    "type=registry,ref=ghcr.io/kl3init/memoryos-api:buildcache",
    "type=registry,ref=ghcr.io/kl3init/memoryos-worker:buildcache",
  ]
}

target "api" {
  inherits = ["_backend"]
  target = "api"
  tags = [RELEASE_TAG != "" ? "ghcr.io/kl3init/memoryos-api:${RELEASE_TAG}" : "memoryos-api:sha-${VCS_REF}"]
  # The full API cache also covers the Gradle stage that both targets share.
  cache-to = RELEASE_TAG != "" ? ["type=registry,ref=ghcr.io/kl3init/memoryos-api:buildcache,mode=max,ignore-error=true"] : []
}

target "worker" {
  inherits = ["_backend"]
  target = "worker"
  tags = [RELEASE_TAG != "" ? "ghcr.io/kl3init/memoryos-worker:${RELEASE_TAG}" : "memoryos-worker:sha-${VCS_REF}"]
  cache-to = RELEASE_TAG != "" ? ["type=registry,ref=ghcr.io/kl3init/memoryos-worker:buildcache,mode=min,ignore-error=true"] : []
}
