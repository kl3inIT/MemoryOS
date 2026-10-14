# Channel verification matrix

| Contract | Test |
| --- | --- |
| A bot starts switched off with its own service account named after it and an encrypted secret; it is audited | `PostgresChannelConnectionTest.aBotStartsSwitchedOffWithItsOwnServiceAccountAndAnEncryptedSecret` |
| One app serves one bot; two apps make two bots of the Tenant, each with its own account | `PostgresChannelConnectionTest.oneAppServesOneBotAndTwoAppsMakeTwoBots` |
| Switching on or off and replacing the secret follow the revision; an unknown bot is not found | `PostgresChannelConnectionTest.switchingAndReplacingTheSecretFollowTheRevision` |
| Removing a bot retires its account and keeps its name | `PostgresChannelConnectionTest.removingABotRetiresItsAccountAndKeepsItsName` |
| The name, app id and language are checked; a member without `CHANNELS_MANAGE` neither reads nor adds a bot | `PostgresChannelConnectionTest.inputIsCheckedAndOnlyAHolderOfTheCapabilityManagesBots` |
| A missing key fails the bots alone | `PostgresChannelConnectionTest.aMissingKeyFailsTheBotsAlone` |
| An app the platform refuses is not stored, nor is its new secret; a test stores nothing and needs the capability | `PostgresChannelConnectionTest.anAppThePlatformRefusesIsNotStoredAndATestStoresNothing` |
| The list carries what the process that holds a bot last wrote | `PostgresChannelConnectionTest.theListCarriesWhatTheProcessThatHoldsABotLastWrote` |
| The API lists bots with their status and no secret, and needs the capability | `ChannelApiTest.theListNeedsTheCapabilityAndCarriesTheStateButNoSecret` |
| The API's test checks an app; a rejected app is `400 CHANNEL_APP_REJECTED`; a mutation needs the CSRF header | `ChannelApiTest.aTestChecksTheAppAndARejectedAppIsABadRequest` |
| Adding a bot is `201` and the notice language is checked | `ChannelApiTest.addingABotReturnsItCreated` |
| Switching, replacing the secret and removing need a quoted revision; a stale one is `409` | `ChannelApiTest.changesFollowTheRevision` |
| The secret envelope moved to the shared kernel unchanged | `CredentialCipherTest` |
| A direct message runs as its sender and a message handed over twice is answered once | `ChannelInboundServiceTest.aDirectMessageRunsAsItsSenderAndIsAnsweredOnce` |
| A sender without an account is answered as the bot, told to sign in, and the provider is asked once while that is remembered | `ChannelInboundServiceTest.aSenderWithoutAnAccountIsAnsweredAsTheBotAndRememberedForAWhile` |
| A group is answered only when the bot is named, as the bot, with the closing line; a new group message starts a conversation | `ChannelInboundServiceTest.aGroupIsAnsweredOnlyWhenTheBotIsNamedAndAsTheBot` |
| A reply to the bot's answer or to the question continues its conversation, however old and whoever replies | `ChannelInboundServiceTest.aReplyContinuesItsConversationHoweverOldAndWhoeverReplies` |
| A direct conversation ends after the idle window and on `/new` | `ChannelInboundServiceTest.aDirectConversationEndsAfterTheIdleWindowOrOnTheNewCommand` |
| A conversation is continued only by the account it belongs to | `ChannelInboundServiceTest.aConversationIsContinuedOnlyByWhoeverItBelongsTo` |
| An outsider, a message that is not text and an external group are refused with a notice and a reason, and no turn runs | `ChannelInboundServiceTest.outsidersOtherMessageTypesAndExternalGroupsAreRefusedWithANotice` |
| A bot of another organisation than its event names, or that is switched off, answers nobody | `ChannelInboundServiceTest.aBotOfAnotherOrganisationOrThatIsSwitchedOffAnswersNobody` |
| A refused or failed turn becomes a notice and a recorded reason | `ChannelInboundServiceTest.aTurnThatIsRefusedOrFailsBecomesANoticeAndARecordedReason` |
| Old message ids are swept and a recent one is kept | `ChannelInboundServiceTest.oldMessageIdsAreSweptAndARecentOneIsKept` |
| A bot switched on is opened with its decrypted secret and its state is written; a second pass keeps it | `ChannelSupervisorTest.aBotSwitchedOnIsOpenedWithItsSecretAndItsStateIsWritten` |
| A message is answered off the adapter's thread | `ChannelSupervisorTest.aMessageIsAnsweredOffTheAdaptersThreadAndMarksTheConnection` |
| A bot whose revision changed is reopened after its old transport closed; one switched off is closed | `ChannelSupervisorTest.aBotSwitchedOffOrChangedIsClosedOrReopened` |
| A dropped transport is reopened; a failed open is recorded and retried | `ChannelSupervisorTest.aDroppedTransportIsReopenedAndAFailedOpenIsRecordedAndRetried` |
| Stopping closes every connection | `ChannelSupervisorTest.stoppingClosesEveryConnection` |
| The channels process starts from its own composition with everything a turn reaches, runs the supervisor and reports ready | `ChannelsApplicationTest.startsWithWhatATurnNeedsAndReportsReady` |
| The channels process scans only itself, names no module's implementation and imports only what the channel capability publishes for it | `ChannelsDependencyRulesTest` |
| A Lark group message naming the bot is addressed, its mention removed and another's kept, with the sender's and the app's organisations from the event | `LarkChannelAdapterTest.aGroupMessageNamingTheBotIsAddressedWithTheMentionRemovedAndBothOrganisations` |
| A Lark direct message is addressed and a reply names what it replies to | `LarkChannelAdapterTest.aDirectMessageIsAddressedAndAReplyNamesWhatItRepliesTo` |
| A Lark message that is not text carries no text; a group message without the bot is not addressed | `LarkChannelAdapterTest.aMessageThatIsNotTextCarriesNoTextAndAGroupMessageWithoutTheBotIsNotAddressed` |
| Another person's mention key becomes their name; each region has its address | `LarkChannelAdapterTest.anotherPersonsMentionKeyBecomesTheirName`, `eachRegionHasItsAddress` |
| The reply card has no header, streams the answer with rising sequences, ends with its sources and closing line, and closes its streaming with a one-line summary | `LarkReplyCardTest.theCardHasNoHeaderStreamsTheAnswerAndClosesWithItsSources` |
| A failure keeps what was written and adds the notice | `LarkReplyCardTest.aFailureKeepsWhatWasWrittenAndAddsTheNotice` |
| A write after Lark closed the stream reopens it once | `LarkReplyCardTest.aWriteAfterLarkClosedTheStreamReopensItOnce` |
| A card Lark refuses fails before any reply is sent | `LarkReplyCardTest.aCardLarkRefusesIsReportedAndNothingIsSent` |
| Mention tags are written out, long text is cut, the summary is one line | `LarkReplyCardTest.mentionTagsAreWrittenOutLongTextIsCutAndTheSummaryIsOneLine` |

The bridge to Chat (`ChannelTurns`: session, leaf, turn, stream, saved answer) has no test of its own; it is
exercised end to end on staging with the Lark adapter (plan step 4), as is the adapter's connection, streaming reply,
e-mail and external-chat calls, which need a live Lark tenant.
