# Channel verification matrix

| Contract | Test |
| --- | --- |
| A bot starts switched off with its own service account named after it, its provider settings and an encrypted secret; it is audited | `PostgresChannelConnectionTest.aBotStartsSwitchedOffWithItsOwnServiceAccountAndAnEncryptedSecret` |
| The app stored is the one the platform names; a second bot of it is refused, and a secret of another app replaces nothing | `PostgresChannelConnectionTest.theAppIsTheOneThePlatformNamesAndASecretOfAnotherAppReplacesNothing` |
| One app serves one bot; two apps make two bots of the Tenant, each with its own account | `PostgresChannelConnectionTest.oneAppServesOneBotAndTwoAppsMakeTwoBots` |
| Switching on or off and replacing the secret follow the revision; an unknown bot is not found | `PostgresChannelConnectionTest.switchingAndReplacingTheSecretFollowTheRevision` |
| Removing a bot retires its account and keeps its name | `PostgresChannelConnectionTest.removingABotRetiresItsAccountAndKeepsItsName` |
| The name, secret and language are checked; a member without `CHANNELS_MANAGE` neither reads nor adds a bot | `PostgresChannelConnectionTest.inputIsCheckedAndOnlyAHolderOfTheCapabilityManagesBots` |
| A Lark app's id and region are checked before Lark is asked; Lark's features | `LarkChannelAdapterTest.anAppIsCheckedForItsShapeBeforeLarkIsAsked` |
| A missing key fails the bots alone | `PostgresChannelConnectionTest.aMissingKeyFailsTheBotsAlone` |
| An app the platform refuses is not stored, nor is its new secret; a test stores nothing and needs the capability | `PostgresChannelConnectionTest.anAppThePlatformRefusesIsNotStoredAndATestStoresNothing` |
| The list carries what the process that holds a bot last wrote | `PostgresChannelConnectionTest.theListCarriesWhatTheProcessThatHoldsABotLastWrote` |
| The API lists bots with their status and no secret, and needs the capability | `ChannelApiTest.theListNeedsTheCapabilityAndCarriesTheStateButNoSecret` |
| The API's test checks an app; a rejected app is `400 CHANNEL_APP_REJECTED`; a mutation needs the CSRF header | `ChannelApiTest.aTestChecksTheAppAndARejectedAppIsABadRequest` |
| Adding a bot is `201` and the notice language is checked | `ChannelApiTest.addingABotReturnsItCreated` |
| Switching, replacing the secret and removing need a quoted revision; a stale one is `409` | `ChannelApiTest.changesFollowTheRevision` |
| The secret envelope moved to the shared kernel unchanged | `CredentialCipherTest` |
| A direct message runs as its sender and a message handed over twice is answered once; the message and each of its steps are spans; the answer ends with the link to its session on the web | `ChannelInboundServiceTest.aDirectMessageRunsAsItsSenderAndIsAnsweredOnce` |
| A sender without an account is answered as the bot, told to sign in, and the provider is asked once while that is remembered | `ChannelInboundServiceTest.aSenderWithoutAnAccountIsAnsweredAsTheBotAndRememberedForAWhile` |
| A group is answered only when the bot is named, as the bot, with the closing line; a new group message starts a conversation | `ChannelInboundServiceTest.aGroupIsAnsweredOnlyWhenTheBotIsNamedAndAsTheBot` |
| A reply to the bot's answer or to the question continues its conversation, however old and whoever replies | `ChannelInboundServiceTest.aReplyContinuesItsConversationHoweverOldAndWhoeverReplies` |
| A direct conversation ends after the idle window and on `/new` | `ChannelInboundServiceTest.aDirectConversationEndsAfterTheIdleWindowOrOnTheNewCommand` |
| A conversation is continued only by the account it belongs to | `ChannelInboundServiceTest.aConversationIsContinuedOnlyByWhoeverItBelongsTo` |
| An outsider, a message that is not text and an external group are refused with a notice and a reason, and no turn runs | `ChannelInboundServiceTest.outsidersOtherMessageTypesAndExternalGroupsAreRefusedWithANotice` |
| A bot of another organisation than its event names, or that is switched off, answers nobody | `ChannelInboundServiceTest.aBotOfAnotherOrganisationOrThatIsSwitchedOffAnswersNobody` |
| On a platform without organisations a stranger is told how to link once a minute and not answered | `ChannelInboundServiceTest.onAPlatformWithoutOrganisationsAStrangerIsToldHowToLinkOnceAMinuteAndNotAnswered` |
| A live code links its sender once, who is then answered as themself; a used code is wrong | `ChannelInboundServiceTest.aLiveCodeLinksItsSenderOnceWhoIsThenAnsweredAsThemself` |
| An expired code, or one sent in a group, does not link | `ChannelInboundServiceTest.anExpiredCodeOrOneSentInAGroupDoesNotLink` |
| Five wrong codes in an hour lock the sender out, even with a live code | `ChannelInboundServiceTest.aSenderWithFiveWrongCodesInAnHourIsNotHeardEvenWithALiveOne` |
| A linked sender in a group is answered as the bot; a stranger there is told how to link | `ChannelInboundServiceTest.aLinkedSenderInAGroupIsAnsweredAsTheBotAndAStrangerIsToldHowToLink` |
| A link is ignored once its member is not active | `ChannelInboundServiceTest.aLinkIsLeftOutOnceItsMemberIsNoLongerActive` |
| A linked sender's wrong codes count toward the lock and are not answered as questions | `ChannelInboundServiceTest.aLinkedSendersWrongCodesCountTowardTheLockAndAreNotAnsweredAsQuestions` |
| Codes still being tried count toward the lock | `ChannelInboundServiceTest.codesStillBeingTriedCountTowardTheLock` |
| A platform without organisations never answers from an e-mail or a remembered account | `ChannelInboundServiceTest.aPlatformWithoutOrganisationsNeverAnswersFromAnEmailOrARememberedAccount` |
| An account moved to another member never continues the first member's conversation, by reply or by a new message | `ChannelInboundServiceTest.anAccountMovedToAnotherMemberNeverContinuesTheFirstMembersConversation` |
| A code made for one bot does not link on another | `ChannelLinkServiceTest.aCodeMadeForOneBotDoesNotLinkOnAnother` |
| A code is eight unambiguous characters, live ten minutes, kept as a hash | `ChannelLinkServiceTest.aCodeIsEightUnambiguousCharactersLiveTenMinutesAndKeptOnlyAsAHash` |
| A new code replaces the member's older one, and a code is used once | `ChannelLinkServiceTest.aNewCodeReplacesTheMembersOlderOneAndACodeIsUsedOnce` |
| An expired code does not link | `ChannelLinkServiceTest.anExpiredCodeDoesNotLink` |
| One account links to one member and a member to one account; relinking replaces | `ChannelLinkServiceTest.oneAccountLinksToOneMemberAndAMemberToOneAccountRelinkingReplaces` |
| A member sees the bots that take a link, their own link only, and unlinks | `ChannelLinkServiceTest.theMemberSeesTheBotsThatTakeALinkAndUnlinks` |
| A bot of a platform with organisations, or no bot, takes no link | `ChannelLinkServiceTest.aBotOfAPlatformWithOrganisationsOrOfNoBotTakesNoLink` |
| Codes and links go with the membership and with the bot | `ChannelLinkServiceTest.codesAndLinksGoWithTheMembershipAndWithTheBot` |
| A message is a code only when it is one code alone | `ChannelLinkServiceTest.aMessageIsReadAsACodeOnlyWhenItIsOneCodeAlone` |
| The API makes a code (`201`, CSRF), lists a member's links and unlinks; another bot is `404` | `ChannelApiTest.aMemberMakesACodeSeesTheirLinksAndUnlinks` |
| A reply that cannot be rewritten gets no partial text and the whole answer in pieces | `ChannelInboundServiceTest.aReplyThatCannotBeRewrittenGetsTheWholeAnswerInPiecesAndNoPartialText` |
| An answer is cut at the last paragraph, line, sentence or space that fits, never inside a character, and keeps every word | `ChannelTextTest` |
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
| A sender past the minute's rate is refused and told to wait | `ChannelInboundServiceTest.aSenderPastTheMinutesRateIsToldToWait` |
| A turn run as the bot is told the asker's name as a plain label; a sender's own turn is not | `ChannelInboundServiceTest.aTurnRunAsTheBotIsToldTheAskersNameAsALabel` |
| A question waits for its conversation's running turn, is acknowledged once and asks at the new leaf | `ChannelTurnsTest.aQuestionWaitsForTheTurnItsConversationIsRunningAndAsksAtTheNewLeaf` |
| A question stops waiting at its bound; another refusal is not waited for | `ChannelTurnsTest.aQuestionStopsWaitingAtItsBoundAndAnotherRefusalIsNotWaitedFor` |
| The page lists bots with one line each; a switch saves on change with the bot's revision; a bot opens its page with its facts and missing scopes | `channels-administration.spec.ts` (Playwright), `channel-status.test.ts` |
| Adding a bot opens the dialog beside the console steps and waits for the App Secret | `channels-administration.spec.ts` |

The bridge to Chat (`ChannelTurns`: session, leaf, turn, stream, saved answer) has no test of its own beyond the
waiting above; it is exercised end to end on staging with the Lark adapter, as are the adapter's connection,
streaming reply, e-mail and external-chat calls, which need a live Lark tenant. Run on staging so far: direct
messages from a mapped member answered as that member, the session opened and continued on the web by its
address, a restart of the process while idle, and the load and provider limits
([verification](../increments/active/mem-246-lark-bot/verification.md)). A group message naming the bot, `@all`,
a second message during an answer and the three-account acceptance wait for a person on the Lark test tenant.
