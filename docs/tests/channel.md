# Channel verification matrix

| Contract | Test |
| --- | --- |
| A bot starts switched off with its own service account named after it and an encrypted secret; it is audited | `PostgresChannelConnectionTest.aBotStartsSwitchedOffWithItsOwnServiceAccountAndAnEncryptedSecret` |
| One app serves one bot; two apps make two bots of the Tenant, each with its own account | `PostgresChannelConnectionTest.oneAppServesOneBotAndTwoAppsMakeTwoBots` |
| Switching on or off and replacing the secret follow the revision; an unknown bot is not found | `PostgresChannelConnectionTest.switchingAndReplacingTheSecretFollowTheRevision` |
| Removing a bot retires its account and keeps its name | `PostgresChannelConnectionTest.removingABotRetiresItsAccountAndKeepsItsName` |
| The name, app id and language are checked; a member without `CHANNELS_MANAGE` neither reads nor adds a bot | `PostgresChannelConnectionTest.inputIsCheckedAndOnlyAHolderOfTheCapabilityManagesBots` |
| A missing key fails the bots alone | `PostgresChannelConnectionTest.aMissingKeyFailsTheBotsAlone` |
| The secret envelope moved to the shared kernel unchanged | `CredentialCipherTest` |
