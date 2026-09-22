"""Every value the services require must be named by the composition.

Infisical used to inject its whole environment into the process, so the composition never named
configuration and nothing noticed. With the vault gone from the servers, a value reaches a
container only when its service block names it: the environment file feeds Compose interpolation
and stops there. The first deployment without the vault failed on a placeholder Spring could not
resolve, which is how this test came to exist.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOYMENT = ROOT / "infrastructure/deployment"

# Each service and the resource directories whose configuration it loads.
SERVICES = {
    "api": ("api/src/main/resources", "core/src/main/resources", "connector/src/main/resources"),
    "worker": ("worker/src/main/resources", "core/src/main/resources", "connector/src/main/resources"),
}

OVERLAYS = ("compose.base.yaml", "compose.staging.yaml", "compose.production.yaml")

# The launcher stages the Redis authority as a file reference rather than its contents, so the
# name it exports does not follow the <NAME>_FILE rule the loop applies to every other secret.
DERIVED = {"MEMORYOS_REDIS_TLS_CA_CERTIFICATE": "MEMORYOS_REDIS_TLS_CA_FILE"}

# Values the CI configuration step supplies itself, so the example files leave them blank: the
# release images and the credentials and Tenant identity a server fills in when it is provisioned.
# Keep this in step with the env block of "Verify deployment and monitoring configuration".
RENDERED_BY_CI = {
    "MEMORYOS_RELEASE", "MEMORYOS_API_IMAGE", "MEMORYOS_WORKER_IMAGE", "MEMORYOS_WEB_IMAGE",
    "MEMORYOS_INTERPRETER_IMAGE", "MEMORYOS_INTERPRETER_EXECUTOR_IMAGE",
    "MEMORYOS_POSTGRES_ADMIN_PASSWORD", "MEMORYOS_DATABASE_PASSWORD",
    "MEMORYOS_KEYCLOAK_DATABASE_PASSWORD", "MEMORYOS_KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD",
    "MEMORYOS_TENANT_ID", "MEMORYOS_TENANT_SLUG", "MEMORYOS_TENANT_DISPLAY_NAME",
    "MEMORYOS_INITIAL_TENANT_CHANGE_REFERENCE",
}


def required(directories):
    """Names written as ${NAME}: no default, so an absent value stops the application."""
    names = set()
    for directory in directories:
        for resource in (ROOT / directory).rglob("*.yaml"):
            names |= set(re.findall(r"\$\{(MEMORYOS_[A-Z0-9_]*)\}", resource.read_text(encoding="utf-8")))
    return names


def service_block(text, service):
    match = re.search(r"\n  %s:\n(.*?)(?=\n  [a-z0-9_-]+:\n)" % service, text, re.S)
    return match.group(1) if match else ""


def named_by_compose(service):
    names = set()
    for overlay in OVERLAYS:
        path = DEPLOYMENT / overlay
        if path.exists():
            block = service_block(path.read_text(encoding="utf-8"), service)
            names |= set(re.findall(r"^\s+(MEMORYOS_[A-Z0-9_]*):", block, re.M))
    return names


class ConfigurationReachesTheContainerTest(unittest.TestCase):
    def test_every_required_value_is_named_by_the_service(self):
        for service, directories in SERVICES.items():
            named = named_by_compose(service)
            for name in sorted(required(directories)):
                reached = (name in named
                           or name + "_FILE" in named
                           or DERIVED.get(name) in named)
                self.assertTrue(reached, "%s reads %s but no %s service block names it, "
                                         "%s_FILE or the file it is derived from"
                                         % (service, name, service, name))

    def test_environment_specific_configuration_is_required_rather_than_defaulted(self):
        # A default for one of these is a value that differs per environment quietly taking the
        # other environment's meaning: tokens accepted from the wrong issuer, a browser sent back
        # to the wrong host, an index built for a cluster that has another node count. The
        # deployment should refuse to reserve instead. Addresses inside the composition are the
        # opposite case and are written in compose, where they cannot drift.
        base = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
        for name in ("MEMORYOS_IDENTITY_ISSUER", "MEMORYOS_IDENTITY_JWK_SET_URI",
                     "MEMORYOS_IDENTITY_AUDIENCE", "MEMORYOS_INITIAL_OWNER_SUBJECT",
                     "MEMORYOS_INVITATION_ACTIVATION_REDIRECT_URI", "MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI",
                     "MEMORYOS_MCP_REDIRECT_URI", "MEMORYOS_SESSION_COOKIE_SECURE",
                     "MEMORYOS_SEARCH_REPLICAS", "MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_KEY_VERSION"):
            self.assertTrue("%s: ${%s:?" % (name, name) in base,
                            "%s must be required, not defaulted" % name)

    def test_each_environment_file_documents_what_that_environment_must_carry(self):
        # A server is provisioned from these files, and CI renders the composition against them.
        # A name missing here is a deployment that fails on the machine rather than in review.
        # The image names are the exception: CD writes them from the release bundle.
        base = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
        for environment in ("staging", "production"):
            overlay = (DEPLOYMENT / ("compose.%s.yaml" % environment)).read_text(encoding="utf-8")
            search = (DEPLOYMENT / ("compose.search.%s.yaml" % environment)).read_text(encoding="utf-8")
            demanded = set(re.findall(r"\$\{(MEMORYOS_[A-Z0-9_]*):\?", base + overlay + search))
            example = (DEPLOYMENT / ("%s.env.example" % environment)).read_text(encoding="utf-8")
            # Compose reads an empty value as no value at all, so a bare name satisfies nothing.
            carried = set(re.findall(r"^(MEMORYOS_[A-Z0-9_]*)=.+$", example, re.M))
            self.assertEqual(sorted(demanded - carried - RENDERED_BY_CI), [],
                             "%s.env.example does not carry every required value" % environment)


if __name__ == "__main__":
    unittest.main()
