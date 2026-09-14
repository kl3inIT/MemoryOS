// Per-resource `permissions` maps stamped by the backend from the same decisions its write
// guards enforce. They are affordance hints only: every mutation keeps its own server check.

type PermissionMap = Readonly<Record<string, boolean>>;

// Fail-closed: a missing resource, map or key reads as false, so a control the server has not
// authorized never renders. The key is typed from the resource's own map.
export function can<P extends PermissionMap>(
  resource: { permissions?: P | null } | null | undefined,
  action: keyof P & string,
): boolean {
  return resource?.permissions?.[action] ?? false;
}
