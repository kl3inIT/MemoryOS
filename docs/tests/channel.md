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
| A group is answered only when the bot is named, as the bot, with no closing line; the group's next message continues its conversation | `ChannelInboundServiceTest.aGroupIsAnsweredOnlyWhenTheBotIsNamedAndAsTheBot` |
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
| On a platform like Zalo's (no organisations, no rewriting) a linked direct sender is answered with the link to continue on the web, an unlinked one is told to link, and a group answer has no closing line | `ChannelInboundServiceTest.onAPlatformLikeZalosALinkedSenderIsAnsweredAndAGroupAnswerHasNoClosingLine` |
| A link is ignored once its member is not active | `ChannelInboundServiceTest.aLinkIsLeftOutOnceItsMemberIsNoLongerActive` |
| A linked account is not moved by another member's code, and a live code and a wrong one get the same answer | `ChannelInboundServiceTest.aLinkedAccountIsNotMovedByAnotherCodeAndLearnsNothingOfIt` |
| A used, an expired and a wrong code get the same notice | `ChannelInboundServiceTest.everyCodeThatDoesNotLinkGetsTheSameNotice` |
| Codes still being tried count toward the lock | `ChannelInboundServiceTest.codesStillBeingTriedCountTowardTheLock` |
| A platform without organisations never answers from an e-mail or a remembered account | `ChannelInboundServiceTest.aPlatformWithoutOrganisationsNeverAnswersFromAnEmailOrARememberedAccount` |
| An account relinked to another member, after the first unlinked it, never continues the first member's conversation | `ChannelInboundServiceTest.anAccountRelinkedToAnotherMemberNeverContinuesTheFirstMembersConversation` |
| A code made for one bot does not link on another | `ChannelLinkServiceTest.aCodeMadeForOneBotDoesNotLinkOnAnother` |
| A code is eight unambiguous characters, live ten minutes, kept as a hash | `ChannelLinkServiceTest.aCodeIsEightUnambiguousCharactersLiveTenMinutesAndKeptOnlyAsAHash` |
| A new code replaces the member's older one, and a code is used once | `ChannelLinkServiceTest.aNewCodeReplacesTheMembersOlderOneAndACodeIsUsedOnce` |
| An expired code does not link | `ChannelLinkServiceTest.anExpiredCodeDoesNotLink` |
| A member relinks their own account, but no code takes an account linked to another member until it is unlinked | `ChannelLinkServiceTest.aMemberRelinksTheirOwnAccountButNoCodeTakesAnAccountLinkedToAnotherMember` |
| An account linked to a member no longer active can be linked again | `ChannelLinkServiceTest.anAccountLinkedToAMemberNoLongerActiveCanBeLinkedAgain` |
| The database never moves a link to another member | `ChannelLinkServiceTest.theDatabaseNeverMovesALinkToAnotherMember` |
| A member sees the bots that take a link, their own link only, and unlinks | `ChannelLinkServiceTest.theMemberSeesTheBotsThatTakeALinkAndUnlinks` |
| A bot of a platform with organisations, or no bot, takes no link | `ChannelLinkServiceTest.aBotOfAPlatformWithOrganisationsOrOfNoBotTakesNoLink` |
| Codes and links go with the membership and with the bot | `ChannelLinkServiceTest.codesAndLinksGoWithTheMembershipAndWithTheBot` |
| A message is a code only when it is one code alone | `ChannelLinkServiceTest.aMessageIsReadAsACodeOnlyWhenItIsOneCodeAlone` |
| Linking and unlinking are audited with the member and the bot only, never the platform account or the code; an unlinking that removes nothing is not | `ChannelLinkServiceTest.linkingAndUnlinkingAreAuditedWithTheMemberAndTheBotOnly` |
| The API makes a code (`201`, CSRF), lists a member's links and unlinks; another bot is `404` | `ChannelApiTest.aMemberMakesACodeSeesTheirLinksAndUnlinks` |
| A reply that cannot be rewritten gets no partial text and the whole answer in pieces | `ChannelInboundServiceTest.aReplyThatCannotBeRewrittenGetsTheWholeAnswerInPiecesAndNoPartialText` |
| An answer is cut at the last paragraph, line, sentence or space that fits, never inside a character, and keeps every word | `ChannelTextTest` |
| A refused or failed turn becomes a notice and a recorded reason | `ChannelInboundServiceTest.aTurnThatIsRefusedOrFailsBecomesANoticeAndARecordedReason` |
| Old message ids are swept and a recent one is kept | `ChannelInboundServiceTest.oldMessageIdsAreSweptAndARecentOneIsKept` |
| A bot switched on is opened with its decrypted secret and its state is written; a second pass keeps it | `ChannelSupervisorTest.aBotSwitchedOnIsOpenedWithItsSecretAndItsStateIsWritten` |
| A bot whose revision changed is reopened after its old transport closed; one switched off is closed | `ChannelSupervisorTest.aBotSwitchedOffOrChangedIsClosedOrReopened` |
| A dropped transport is reopened; a failed open is recorded and retried | `ChannelSupervisorTest.aDroppedTransportIsReopenedAndAFailedOpenIsRecordedAndRetried` |
| Stopping closes every connection | `ChannelSupervisorTest.stoppingClosesEveryConnection` |
| A webhook is served before the provider is given its address; a pushed message is claimed before the provider is answered; a request without the secret is refused, an unknown bot unknown, both from the headers alone | `ChannelSupervisorTest.aPushedMessageIsClaimedBeforeTheProviderIsAnswered` |
| A message a transport hands over is claimed on its thread and answered off it; each pass settles what an ended process left unsettled | `ChannelSupervisorTest.aMessageIsClaimedOnTheAdaptersThreadAndAnsweredOffIt`, `eachPassSettlesWhatAnEndedProcessLeftUnsettled`, `ChannelInboundServiceTest.claimsAnEndedProcessLeftUnsettledAreSettledAsInterrupted` |
| A group message that only names the bot gets no notice; an unlinked sender in a group is told only to message the bot directly; an answer sent in part is recorded with its last id and the sender told; whatever fails once the reply is open ends it | `ChannelInboundServiceTest.aGroupMessageThatOnlyNamesTheBotIsLeftUnansweredWithoutANotice`, `anUnlinkedSenderInAGroupIsToldOnlyToMessageTheBotDirectly`, `anAnswerSentInPartKeepsWhatWasSentAndTellsTheSenderTheRestIsMissing`, `whateverFailsOnceTheReplyIsOpenEndsTheReply` |
| A webhook the provider cannot reach is recorded and waits for the bot's next revision | `ChannelSupervisorTest.aWebhookTheProviderCannotReachWaitsForTheBotToChange` |
| A setting changes as the provider accepts it and moves the revision; one it does not have is refused | `PostgresChannelConnectionTest.aSettingChangesWhatTheProviderAcceptsAndMovesTheRevision` |
| The database holds a Zalo bot by its number and how it receives, and refuses anything else | `PostgresChannelConnectionTest.theDatabaseHoldsAZaloBotByItsNumberAndHowItReceives` |
| MEM-273: a bot is bound to an assistant only by a holder of `CHANNELS_MANAGE` who may edit it, moves from another assistant in one step, keeps its revision, is listed by the assistant and named on the bots list; the built-in assistant unbinds; an assistant the caller cannot use is `CHAT_UNAVAILABLE` | `PostgresChannelConnectionTest.aBotIsBoundToAnAssistantByWhoeverManagesBotsAndMayEditIt` |
| MEM-273: moving or unbinding a bot also takes edit rights on the assistant it leaves | `PostgresChannelConnectionTest.movingABotAlsoTakesEditRightsOnTheAssistantItLeaves` |
| MEM-273: removing a bot or its assistant row removes the binding | `PostgresChannelConnectionTest.removingABotOrItsAssistantRowClearsTheBinding` |
| MEM-273: each question is asked through its bot, whose assistant Chat looks up for each question | `ChannelInboundServiceTest.aQuestionIsAskedThroughItsBotWhoseAssistantChatLooksUpForEachQuestion` |
| MEM-273: a conversation continues only with the assistant the bot answers with now | `ChannelTurnsTest.aConversationContinuesOnlyWithTheAssistantTheBotAnswersWithNow` |
| MEM-273 over HTTP: binding answers the bot with its assistant, a refusal is `CHANNEL_ASSISTANT_NOT_EDITABLE` (403), and an assistant's bots are listed without app, secret or account | `ChannelApiTest.aBotIsBoundToAnAssistantAndTheAssistantListsItsBots` |
| MEM-273 step 5: a group chat the bot answered in is listed most recent first with the provider's name, given its own assistant only by a holder of `CHANNELS_MANAGE` who may edit it and the one it leaves, labelled and forgotten (which drops its assistant); an unknown group is `CHANNEL_GROUP_NOT_FOUND`; the assistant lists the group | `PostgresChannelConnectionTest.aGroupChatTheBotAnsweredInIsGivenItsOwnAssistantByWhoeverManagesBotsAndMayEditIt` |
| MEM-273 step 5: forgetting a group whose assistant the caller may not edit is `CHANNEL_ASSISTANT_NOT_EDITABLE`, and the group keeps its assistant and stays known | `PostgresChannelConnectionTest.forgettingAGroupAlsoTakesEditRightsOnTheAssistantItWasGiven` |
| MEM-273 step 5: removing a bot drops its groups and their assistants; removing an assistant row drops the groups' assistants and keeps the groups | `PostgresChannelConnectionTest.removingABotDropsItsGroupsAndTheirAssistants`, `PostgresChannelConnectionTest.removingAnAssistantRowDropsTheGroupsGivenIt` |
| MEM-273 step 5: a group the bot answers in is recorded with the provider's name, its turn names the group, and a direct message names none; an external group is not recorded | `ChannelInboundServiceTest.aGroupTheBotAnswersInIsRecordedAndItsTurnNamesTheGroup`, `ChannelInboundServiceTest.outsidersOtherMessageTypesAndExternalGroupsAreRefusedWithANotice` |
| MEM-273 step 5 over HTTP: a bot's groups are listed, given an assistant, labelled (over 100 characters is 400) and forgotten (204); an unknown group is `CHANNEL_GROUP_NOT_FOUND` (404); an assistant's bots carry `byDefault` and its groups | `ChannelApiTest.aBotsGroupChatsAreListedGivenAnAssistantNamedAndForgotten`, `ChannelApiTest.aBotIsBoundToAnAssistantAndTheAssistantListsItsBots` |
| MEM-273 Kênh tab: lists the assistant's bots; a binder adds an unbound bot at once, moves one bound elsewhere after a confirmation naming both assistants, and removes one; anyone else reads the list; the built-in assistant has no tab | `agents.spec.ts`, `agent-editor.test.tsx` |
| The webhook route asks about the headers before it reads a body, answers `200` after the claim, `403` without the secret and `404` for an unknown bot unread, and `413` past 64 KB without reading it | `ChannelWebhookControllerTest` |
| The channels process serves the webhook route without a session and has the Zalo adapter | `ChannelsApplicationTest.startsWithWhatATurnNeedsAndReportsReady` |
| nginx sends `/hooks/channels/` to the bots' process with a bounded body | `nginx-config.test.mjs` |
| A Zalo bot is its token and Zalo names it; a token of another shape is never sent; a refused token is rejected, an unanswered one unavailable | `ZaloChannelAdapterTest.aBotIsItsTokenAndZaloNamesIt`, `aTokenOfAnotherShapeOrOneZaloRefusesIsNotABot` |
| A failed Zalo call names its code, never the token | `ZaloChannelAdapterTest.aFailedCallNamesItsCodeAndNeverTheToken` |
| A Zalo bot receives by webhook where the deployment has an address, else by polling | `ZaloChannelAdapterTest.aBotReceivesByWebhookWhereTheDeploymentHasAnAddressElseByPolling` |
| A webhook gets its address and a secret of its opening, accepts only Zalo's secret and is removed on close; Zalo's failed check is reported, and tested once when its answer carries none | `ZaloChannelAdapterTest.aWebhookIsGivenItsAddressAndASecretOfItsOpeningAndChecksIt`, `aWebhookZaloCannotReachIsReportedWithZalosOutcome`, `withoutAVerificationInItsAnswerTheWebhookIsTestedOnce` |
| A polling bot drops any webhook and hands over what Zalo has | `ZaloChannelAdapterTest.aPollingBotDropsAnyWebhookAndHandsOverWhatZaloHas` |
| Every Zalo group message addresses the bot (Zalo pushes only those that name it), its mention removed; a bot's message is dropped; an image without a caption is not text | `ZaloChannelAdapterTest.everyGroupMessageAddressesTheBotSinceZaloPushesOnlyTheOnesThatNameIt` |
| The payloads a real bot received (group mention, direct text, sticker, photo with and without a caption) are read as Zalo sent them; a poll answers one update as an object, and no webhook to delete is no failure | `ZaloChannelAdapterTest.whatARealBotReceivedIsReadAsZaloSentIt`, `aPollAnswersOneUpdateAsAnObject` |
| The mention is the bot's whole display name, ignoring case on NFC text, read again at each opening; a sender without a display name is known by their name | `ZaloChannelAdapterTest.theMentionIsTheBotsWholeDisplayNameReadAgainAtEachOpening`, `aSenderWithoutADisplayNameIsKnownByTheirName` |
| The webhook's secret is checked from the headers alone; a 426 from setting the address fails the opening and one on the check does not; a webhook needs an HTTPS address | `ZaloChannelAdapterTest.theSecretIsCheckedFromTheHeadersAlone`, `theDaysChecksSpentLeaveTheAddressSetOnlyWhenTheyRanOutOnTheCheck`, `aWebhookNeedsAnHttpsAddress` |
| An answer cut short keeps what was sent and names the last id; the typing mark stops at its limit | `ZaloChannelAdapterTest.anAnswerCutShortKeepsWhatWasSentAndSaysTheRestIsMissing`, `theTypingMarkStopsAtItsLimitWhateverBecomesOfTheAnswer` |
| An answer's markdown becomes text Zalo shows: links, images, code, headings, quotes, rules and tables | `ZaloTextTest`, `ZaloChannelAdapterTest.zalosMarkdownGetsTextItShows` |
| A Zalo answer is sent in pieces with the asker's name in a group, its sources and closing link as bare addresses, markdown and the typing mark; refused markdown is sent plain; a spent plan is `CHANNEL_PROVIDER_QUOTA`; no message exceeds 2,000 characters | `ZaloChannelAdapterTest.anAnswerIsSentInPiecesWithItsSourcesAndTheTypingMarkMeanwhile`, `aBotOutOfItsPlanSaysSoAndMarkdownZaloRefusesIsSentPlain`, `longPiecesAndAGroupNameNeverExceedWhatAMessageTakes` |
| The Zalo API adds a bot by its token with its transport, answers no region and no token, and changes the transport with the revision | `ChannelApiTest.aZaloBotIsAddedByItsTokenAndItsTransportChangesWithTheRevision` |
| The Zalo integration depends on public capability APIs and no other platform | `ZaloDependencyRulesTest`, `:memoryos-integration-zalo:checkRuntimeClasspathIsolation` |
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
| The linking instructions end with the bare address of Settings › Chat | `ChannelInboundServiceTest.theLinkingInstructionsEndWithTheAddressWhereACodeIsGot` |
| A group's linking notice never silences the instructions in the sender's direct chat; within one chat the notice goes once a minute | `ChannelInboundServiceTest.aGroupsNoticeNeverSilencesTheInstructionsInTheSendersDirectChat` |
| A group has one conversation, run as the bot, shared by its members' questions while recent, apart from direct ones, started over past the idle window or on `/new` | `ChannelInboundServiceTest.aGroupHasOneConversationWhileItIsRecent` |
| A reply in a group continues the conversation of the answer it replies to, however old, and a reply naming a direct message's id never reaches that conversation | `ChannelInboundServiceTest.aReplyInAGroupContinuesTheGroupsConversationAndNeverReachesADirectOne` |
| A sender past the minute's rate is refused and told to wait | `ChannelInboundServiceTest.aSenderPastTheMinutesRateIsToldToWait` |
| A turn run as the bot is told the asker's name as a plain label; a sender's own turn is not | `ChannelInboundServiceTest.aTurnRunAsTheBotIsToldTheAskersNameAsALabel` |
| A channel turn names its platform and whether it is a group, which the system prompt states, with the group's `[name]:` label explained; a web turn gets no such section | `ChannelInboundServiceTest.aLinkedSenderInAGroupIsAnsweredAsTheBotAndAStrangerIsToldHowToLink`, `ChannelTurnsTest.aQuestionWaitsForTheTurnItsConversationIsRunningAndAsksAtTheNewLeaf`, `ChatChannelPromptTest` |
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
a second message during an answer and the three-account acceptance wait for a person on the Lark test tenant. The
Zalo adapter runs against a local stand-in for Zalo; a real bot, its group mention and the webhook on staging are
the probe and acceptance of [MEM-263](../increments/active/mem-263-zalo-bot/plan.md).
