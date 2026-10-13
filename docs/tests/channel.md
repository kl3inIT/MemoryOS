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
| A direct message runs as its sender and a message handed over twice is answered once | `ChannelInboundServiceTest.aDirectMessageRunsAsItsSenderAndIsAnsweredOnce` |
| A sender without an account is answered as the bot, told to sign in, and the provider is asked once while that is remembered | `ChannelInboundServiceTest.aSenderWithoutAnAccountIsAnsweredAsTheBotAndRememberedForAWhile` |
| A group is answered only when the bot is named, as the bot, with the closing line; a new group message starts a conversation | `ChannelInboundServiceTest.aGroupIsAnsweredOnlyWhenTheBotIsNamedAndAsTheBot` |
| A reply to the bot's answer or to the question continues its conversation, however old and whoever replies | `ChannelInboundServiceTest.aReplyContinuesItsConversationHoweverOldAndWhoeverReplies` |
| A direct conversation ends after the idle window and on `/new` | `ChannelInboundServiceTest.aDirectConversationEndsAfterTheIdleWindowOrOnTheNewCommand` |
| A conversation is continued only by the account it belongs to | `ChannelInboundServiceTest.aConversationIsContinuedOnlyByWhoeverItBelongsTo` |
| An outsider, a message that is not text and an external group are refused with a notice and a reason, and no turn runs | `ChannelInboundServiceTest.outsidersOtherMessageTypesAndExternalGroupsAreRefusedWithANotice` |
| A bot whose organisation is not known, or that is switched off, answers nobody | `ChannelInboundServiceTest.aBotWhoseOrganisationIsNotKnownOrThatIsSwitchedOffAnswersNobody` |
| A refused or failed turn becomes a notice and a recorded reason | `ChannelInboundServiceTest.aTurnThatIsRefusedOrFailsBecomesANoticeAndARecordedReason` |
| Old message ids are swept and a recent one is kept | `ChannelInboundServiceTest.oldMessageIdsAreSweptAndARecentOneIsKept` |
| A bot switched on is opened with its decrypted secret and its state is written; a second pass keeps it | `ChannelSupervisorTest.aBotSwitchedOnIsOpenedWithItsSecretAndItsStateIsWritten` |
| A message is answered off the adapter's thread | `ChannelSupervisorTest.aMessageIsAnsweredOffTheAdaptersThreadAndMarksTheConnection` |
| A bot whose revision changed is reopened after its old transport closed; one switched off is closed | `ChannelSupervisorTest.aBotSwitchedOffOrChangedIsClosedOrReopened` |
| A dropped transport is reopened; a failed open is recorded and retried | `ChannelSupervisorTest.aDroppedTransportIsReopenedAndAFailedOpenIsRecordedAndRetried` |
| Stopping closes every connection | `ChannelSupervisorTest.stoppingClosesEveryConnection` |
| The channels process starts from its own composition with everything a turn reaches, runs the supervisor and reports ready | `ChannelsApplicationTest.startsWithWhatATurnNeedsAndReportsReady` |
| The channels process scans only itself, names no module's implementation and imports only what the channel capability publishes for it | `ChannelsDependencyRulesTest` |

The bridge to Chat (`ChannelTurns`: session, leaf, turn, stream, saved answer) has no test of its own; it is
exercised end to end on staging with the Lark adapter (plan step 4).
