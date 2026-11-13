# LandClaim

Lightweight crossplay land claims for Paper 1.21.x (Java + Bedrock via Geyser/Floodgate).

- Players open everything from the **Claim Menu** item (right-click it). No typed commands needed.
- Bedrock players get Floodgate forms with icons, Java players get a chest menu.
- Vault is optional (only for prices, rent, sell, tax). Floodgate is optional.

## Commands
| Command | Who | What |
|---|---|---|
| `/confirm` | everyone | Same as the Confirm button while previewing a claim/expansion |
| `/claimcancel` | everyone | Cancel the preview |
| `/claimmenu` | everyone | Open the menu / get the menu item back |
| `/deleteclaim <landname>[@owner]` | op | Delete a claim by name |
| `/ownerchange <username> [landname]` | op | Give the claim you stand in (or the named one) to a player |
| `/claimreload` | op | Reload config |

Permissions: `landclaim.use` (default), `landclaim.bypass` (op), `landclaim.admin` (op)

## Build
GitHub: push to `main`, open the Actions tab, download the `LandClaim` artifact (LandClaim.jar).
Local: `mvn clean package` -> `target/LandClaim.jar`.
Tag a commit `v1.0.0` to also attach the jar to a GitHub Release.
