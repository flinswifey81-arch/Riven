package com.shai.riven.ui.arcade.cosmic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmicMischiefEngineTest {
    @Test
    fun deterministicDealHasSevenCardHandsAndConservesTheDeck() {
        val first = CosmicMischiefEngine.newGame(seed = 20261003L)
        val second = CosmicMischiefEngine.newGame(seed = 20261003L)

        assertEquals(first, second)
        assertEquals(COSMIC_STARTING_HAND_SIZE, first.hand(CosmicPlayer.SHAI).size)
        assertEquals(COSMIC_STARTING_HAND_SIZE, first.hand(CosmicPlayer.RIVEN).size)
        assertTrue(first.topDiscard.kind is CosmicCardKind.Number)
        assertEquals(CosmicPlayer.SHAI, first.turn)
        assertConserved(first)
    }

    @Test
    fun legalPlayAdvancesTurnAndRepeatedStaleTapCannotApplyTwice() {
        val state = rigState(
            shaiIds = listOf(15, 22),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.SHAI,
        )
        val command = CosmicCommand(
            actor = CosmicPlayer.SHAI,
            expectedRevision = state.revision,
            action = CosmicAction.PlayCard(cardId = 15),
        )

        val first = CosmicMischiefEngine.apply(CosmicMischiefSession(state), command)
        val repeated = CosmicMischiefEngine.apply(first.session, command)

        assertTrue(first.changed)
        assertEquals(CosmicPlayer.RIVEN, first.session.game.turn)
        assertEquals(15, first.session.game.topDiscard.id)
        assertFalse(repeated.changed)
        assertTrue(repeated.message.contains("stale"))
        assertEquals(first.session, repeated.session)
        assertConserved(first.session.game)
    }

    @Test
    fun eclipseDoubleTroubleAndRewriteTheStarsHaveAuthoritativeEffects() {
        val eclipse = applyOne(
            rigState(listOf(40, 22), listOf(3, 31), topId = 5),
            CosmicAction.PlayCard(40),
        )
        assertEquals(CosmicPlayer.SHAI, eclipse.turn)
        assertEquals(2, eclipse.hand(CosmicPlayer.RIVEN).size)
        assertTrue(eclipse.events.any { it.type == CosmicEventType.ECLIPSE_PLAYED })

        val drawTwoBefore = rigState(listOf(44, 22), listOf(3, 31), topId = 5)
        val doubleTrouble = applyOne(drawTwoBefore, CosmicAction.PlayCard(44))
        assertEquals(CosmicPlayer.SHAI, doubleTrouble.turn)
        assertEquals(drawTwoBefore.hand(CosmicPlayer.RIVEN).size + 2, doubleTrouble.hand(CosmicPlayer.RIVEN).size)
        assertTrue(doubleTrouble.events.any { it.type == CosmicEventType.DOUBLE_TROUBLE_PLAYED })

        val rewrite = applyOne(
            rigState(listOf(48, 22), listOf(3, 31), topId = 5),
            CosmicAction.PlayCard(48, chosenColor = CosmicColor.VIOLET),
        )
        assertEquals(CosmicColor.VIOLET, rewrite.activeColor)
        assertEquals(CosmicPlayer.RIVEN, rewrite.turn)
        assertTrue(rewrite.events.any { it.type == CosmicEventType.STARS_REWRITTEN })
        assertConserved(eclipse)
        assertConserved(doubleTrouble)
        assertConserved(rewrite)
    }

    @Test
    fun firstEmptyHandWinsForEitherPlayerAndCosmicCanActuallyBeLost() {
        val shaiState = rigState(listOf(15), listOf(3, 31), topId = 5, turn = CosmicPlayer.SHAI)
        val shaiWin = applyOne(shaiState, CosmicAction.PlayCard(15), CosmicPlayer.SHAI)
        assertEquals(CosmicStatus.SHAI_WON, shaiWin.status)

        val rivenState = rigState(listOf(3, 31), listOf(15), topId = 5, turn = CosmicPlayer.RIVEN)
        val rivenWin = applyOne(rivenState, CosmicAction.PlayCard(15), CosmicPlayer.RIVEN)
        assertEquals(CosmicStatus.RIVEN_WON, rivenWin.status)
        assertConserved(shaiWin)
        assertConserved(rivenWin)
    }

    @Test
    fun shaiCheatCreatesPublicTellAndCaughtPenaltyRestoresExtraCard() {
        val state = rigState(
            shaiIds = listOf(1, 17, 22),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.SHAI,
        )
        val cheated = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(
                CosmicPlayer.SHAI,
                state.revision,
                CosmicAction.SneakExtraDiscard(primaryCardId = 1, extraCardId = 17),
            ),
        )

        assertTrue(cheated.changed)
        assertEquals(CosmicPlayer.RIVEN, CosmicMischiefEngine.decisionPlayer(cheated.session.game))
        assertEquals(CosmicPlayer.SHAI, CosmicMischiefEngine.publicObservation(cheated.session.game).visibleTell?.accused)
        assertEquals(17, cheated.session.game.topDiscard.id)

        val illegalMoveInsteadOfDecision = CosmicMischiefEngine.apply(
            cheated.session,
            CosmicCommand(
                CosmicPlayer.RIVEN,
                cheated.session.game.revision,
                CosmicAction.DrawCard,
            ),
        )
        assertFalse(illegalMoveInsteadOfDecision.changed)
        assertEquals(cheated.session, illegalMoveInsteadOfDecision.session)

        val caught = CosmicMischiefEngine.apply(
            cheated.session,
            CosmicCommand(
                CosmicPlayer.RIVEN,
                cheated.session.game.revision,
                CosmicAction.CallOut,
            ),
        )
        val result = caught.session.game
        assertTrue(caught.changed)
        assertEquals(1, result.topDiscard.id)
        assertEquals(CosmicColor.RUBY, result.activeColor)
        assertTrue(result.hand(CosmicPlayer.SHAI).any { it.id == 17 })
        assertEquals(
            state.hand(CosmicPlayer.SHAI).size + COSMIC_CAUGHT_CHEAT_PENALTY - 1,
            result.hand(CosmicPlayer.SHAI).size,
        )
        assertEquals(CosmicPlayer.RIVEN, result.turn)
        assertNull(result.pendingCheat)
        assertTrue(result.events.any { it.type == CosmicEventType.CHEAT_CAUGHT })
        assertConserved(result)
    }

    @Test
    fun rivenCanCheatAndShaiGetsUntimedCalloutOrDeclineDecision() {
        val state = rigState(
            shaiIds = listOf(3, 31),
            rivenIds = listOf(1, 17, 22),
            topId = 5,
            turn = CosmicPlayer.RIVEN,
        )
        val cheated = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(
                CosmicPlayer.RIVEN,
                state.revision,
                CosmicAction.SneakExtraDiscard(primaryCardId = 1, extraCardId = 17),
            ),
        )
        val persistedDecision = CosmicMischiefSnapshotCodec.decode(
            CosmicMischiefSnapshotCodec.encode(cheated.session),
        )

        assertEquals(CosmicPlayer.SHAI, CosmicMischiefEngine.decisionPlayer(cheated.session.game))
        assertEquals(cheated.session, persistedDecision)
        assertNotEquals(null, cheated.session.game.pendingCheat)

        val caught = CosmicMischiefEngine.apply(
            cheated.session,
            CosmicCommand(CosmicPlayer.SHAI, cheated.session.game.revision, CosmicAction.CallOut),
        )
        assertTrue(caught.changed)
        assertEquals(CosmicPlayer.SHAI, caught.session.game.turn)
        assertTrue(caught.session.game.hand(CosmicPlayer.RIVEN).any { it.id == 17 })
        assertConserved(caught.session.game)
    }

    @Test
    fun lettingCheatSlideCanFinalizeProvisionalEmptyHandWin() {
        val state = rigState(
            shaiIds = listOf(1, 17),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.SHAI,
        )
        val cheated = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(
                CosmicPlayer.SHAI,
                state.revision,
                CosmicAction.SneakExtraDiscard(primaryCardId = 1, extraCardId = 17),
            ),
        )
        assertEquals(CosmicStatus.PLAYING, cheated.session.game.status)
        assertTrue(cheated.session.game.hand(CosmicPlayer.SHAI).isEmpty())

        val escaped = CosmicMischiefEngine.apply(
            cheated.session,
            CosmicCommand(CosmicPlayer.RIVEN, cheated.session.game.revision, CosmicAction.DeclineCallout),
        )
        assertEquals(CosmicStatus.SHAI_WON, escaped.session.game.status)
        assertTrue(escaped.events.any { it.type == CosmicEventType.CHEAT_ESCAPED })
        assertTrue(escaped.events.any { it.type == CosmicEventType.GAME_WON })
        assertConserved(escaped.session.game)
    }

    @Test
    fun falseCalloutDrawsOneAndYieldsTurn() {
        val state = rigState(listOf(1, 22), listOf(3, 31), topId = 5, turn = CosmicPlayer.SHAI)
        val result = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.SHAI, state.revision, CosmicAction.CallOut),
        )

        assertTrue(result.changed)
        assertEquals(state.hand(CosmicPlayer.SHAI).size + COSMIC_FALSE_CALLOUT_PENALTY, result.session.game.hand(CosmicPlayer.SHAI).size)
        assertEquals(CosmicPlayer.RIVEN, result.session.game.turn)
        assertTrue(result.events.any { it.type == CosmicEventType.FALSE_CALLOUT })
        assertConserved(result.session.game)
    }

    @Test
    fun opponentObservationContainsOwnHandCountsAndTellButNeverHiddenUserHand() {
        val state = rigState(
            shaiIds = listOf(1, 17, 22),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.SHAI,
        )
        val view = CosmicMischiefEngine.opponentObservation(state)

        assertEquals(state.hand(CosmicPlayer.RIVEN), view.ownHand)
        assertEquals(listOf(3, 2), view.public.handCounts)
        assertTrue(view.ownHand.none { it in state.hand(CosmicPlayer.SHAI) })
        assertNull(view.public.visibleTell)
        assertNull(view.public.pendingTrade)
    }

    @Test
    fun opponentObservationIsDeeplyDetachedAndRejectsCollectionMutation() {
        val state = rigState(
            shaiIds = listOf(1, 17, 22),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.RIVEN,
        ).copy(
            events = mutableListOf(CosmicEvent(CosmicEventType.DEAL_STARTED, message = "Detached")),
            grudges = mutableListOf(2, 4),
        )
        val original = state.copy(
            drawPile = state.drawPile.toList(),
            discardPile = state.discardPile.toList(),
            hands = state.hands.map { it.toList() },
            events = state.events.toList(),
            grudges = state.grudges.toList(),
        )
        val view = CosmicMischiefEngine.opponentObservation(state)

        assertMutationRejected { (view.ownHand as MutableList<CosmicCard>).clear() }
        assertMutationRejected { (view.public.handCounts as MutableList<Int>).clear() }
        assertMutationRejected { (view.public.grudges as MutableList<Int>)[0] = 99 }
        assertMutationRejected { (view.public.recentEvents as MutableList<CosmicEvent>).clear() }
        assertEquals(original, state)
        assertEquals(listOf(3, 31), view.ownHand.map(CosmicCard::id))
        assertEquals(listOf(2, 4), view.public.grudges)
    }

    @Test
    fun emptyDrawPileRecyclesDiscardAndPassesWhenNoCardCanBeRecycled() {
        val recyclable = exhaustedState(
            shaiIds = listOf(11),
            discardIds = listOf(20, 21, 5),
            turn = CosmicPlayer.SHAI,
        )
        val recycled = applyOne(recyclable, CosmicAction.DrawCard)
        assertEquals(listOf(5), recycled.discardPile.map(CosmicCard::id))
        assertEquals(2, recycled.hand(CosmicPlayer.SHAI).size)
        assertEquals(1, recycled.drawPile.size)
        assertConserved(recycled)

        val exhausted = exhaustedState(
            shaiIds = listOf(11),
            discardIds = listOf(5),
            turn = CosmicPlayer.SHAI,
        )
        assertTrue(
            exhausted.hand(CosmicPlayer.SHAI).none {
                CosmicMischiefEngine.isPlayable(it, exhausted.topDiscard, exhausted.activeColor)
            },
        )
        val passed = applyOne(exhausted, CosmicAction.DrawCard)
        assertEquals(CosmicPlayer.RIVEN, passed.turn)
        assertEquals(exhausted.hands, passed.hands)
        assertEquals(CosmicEventType.NO_CARD_PASS, passed.events.last().type)
        assertTrue(passed.events.last().message.contains("no card available"))
        assertEquals(
            CosmicMischiefSession(passed),
            CosmicMischiefSnapshotCodec.decode(
                CosmicMischiefSnapshotCodec.encode(CosmicMischiefSession(passed)),
            ),
        )
        assertConserved(passed)
    }

    @Test
    fun exhaustedDeckStillResolvesCaughtCheatAndProtectsPrimaryDiscard() {
        val state = exhaustedState(
            shaiIds = listOf(1, 17),
            discardIds = listOf(5),
            turn = CosmicPlayer.SHAI,
        )
        val cheated = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(
                CosmicPlayer.SHAI,
                state.revision,
                CosmicAction.SneakExtraDiscard(primaryCardId = 1, extraCardId = 17),
            ),
        ).session

        val caught = CosmicMischiefEngine.apply(
            cheated,
            CosmicCommand(CosmicPlayer.RIVEN, cheated.game.revision, CosmicAction.CallOut),
        )

        assertTrue(caught.changed)
        assertNull(caught.session.game.pendingCheat)
        assertEquals(1, caught.session.game.topDiscard.id)
        assertTrue(caught.session.game.hand(CosmicPlayer.SHAI).any { it.id == 17 })
        assertTrue(caught.events.single().message.contains("1 of 2 available"))
        assertConserved(caught.session.game)
    }

    @Test
    fun exhaustedDeckStillResolvesDrawTwoAndBetrayal() {
        val drawTwoState = exhaustedState(
            shaiIds = listOf(44, 22),
            discardIds = listOf(5),
            turn = CosmicPlayer.SHAI,
        )
        val drawTwo = applyOne(drawTwoState, CosmicAction.PlayCard(44))
        assertEquals(44, drawTwo.topDiscard.id)
        assertEquals(CosmicPlayer.SHAI, drawTwo.turn)
        assertTrue(drawTwo.events.last().message.contains("1 of 2 available"))
        assertConserved(drawTwo)

        val betrayalState = exhaustedState(
            shaiIds = listOf(22),
            discardIds = listOf(5),
            turn = CosmicPlayer.SHAI,
        )
        val betrayed = respond(offer(betrayalState, offeredId = 22), CosmicTradeDecision.BETRAY)
        assertEquals(CosmicStatus.SHAI_WON, betrayed.status)
        assertTrue(betrayed.events.any { it.type == CosmicEventType.BARGAIN_BETRAYED })
        assertTrue(betrayed.events.any { it.type == CosmicEventType.GAME_WON })
        assertTrue(betrayed.events.any { it.message.contains("no consolation card was available") })
        assertConserved(betrayed)
    }

    @Test
    fun tradeCanBeHonoredRefusedOrBetrayedAsBoundedEvents() {
        val base = rigState(
            shaiIds = listOf(1, 22),
            rivenIds = listOf(3, 31),
            topId = 5,
            turn = CosmicPlayer.SHAI,
        )

        val honoredOffer = offer(base, offeredId = 22)
        val honored = respond(
            honoredOffer,
            CosmicTradeDecision.HONOR,
            returnedId = 31,
        )
        assertTrue(honored.hand(CosmicPlayer.SHAI).any { it.id == 31 })
        assertTrue(honored.hand(CosmicPlayer.RIVEN).any { it.id == 22 })
        assertEquals(1, honored.honoredBargains)
        assertTrue(honored.events.any { it.type == CosmicEventType.BARGAIN_HONORED })

        val refusedOffer = offer(base, offeredId = 22)
        val refused = respond(refusedOffer, CosmicTradeDecision.REFUSE)
        assertEquals(base.hands, refused.hands)
        assertTrue(refused.events.any { it.type == CosmicEventType.TRADE_REFUSED })

        val betrayedOffer = offer(base, offeredId = 22)
        val betrayed = respond(betrayedOffer, CosmicTradeDecision.BETRAY)
        assertFalse(betrayed.hand(CosmicPlayer.SHAI).any { it.id == 22 })
        assertTrue(betrayed.hand(CosmicPlayer.RIVEN).any { it.id == 22 })
        assertEquals(base.hand(CosmicPlayer.SHAI).size, betrayed.hand(CosmicPlayer.SHAI).size)
        assertEquals(1, betrayed.grudges[CosmicPlayer.SHAI.ordinal])
        assertEquals(1, betrayed.betrayedBargains)
        assertTrue(betrayed.events.any { it.type == CosmicEventType.BARGAIN_BETRAYED })
        assertConserved(honored)
        assertConserved(refused)
        assertConserved(betrayed)
    }

    @Test
    fun illegalCardsWrongActorsAndInvalidWildChoicesAreRejected() {
        val state = rigState(listOf(11, 48), listOf(3, 31), topId = 5, turn = CosmicPlayer.SHAI)
        val wrongColor = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.SHAI, state.revision, CosmicAction.PlayCard(11)),
        )
        val wrongActor = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.RIVEN, state.revision, CosmicAction.DrawCard),
        )
        val wildWithoutColor = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.SHAI, state.revision, CosmicAction.PlayCard(48)),
        )

        assertFalse(wrongColor.changed)
        assertFalse(wrongActor.changed)
        assertFalse(wildWithoutColor.changed)
        assertEquals(state, wrongColor.session.game)
        assertEquals(state, wrongActor.session.game)
        assertEquals(state, wildWithoutColor.session.game)
    }

    private fun offer(state: CosmicMischiefState, offeredId: Int): CosmicMischiefState {
        val result = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(CosmicPlayer.SHAI, state.revision, CosmicAction.OfferTrade(offeredId)),
        )
        assertTrue(result.changed)
        return result.session.game
    }

    private fun respond(
        offered: CosmicMischiefState,
        decision: CosmicTradeDecision,
        returnedId: Int? = null,
    ): CosmicMischiefState {
        val result = CosmicMischiefEngine.apply(
            CosmicMischiefSession(offered),
            CosmicCommand(
                CosmicPlayer.RIVEN,
                offered.revision,
                CosmicAction.RespondToTrade(decision, returnedId),
            ),
        )
        assertTrue(result.changed)
        return result.session.game
    }

    private fun applyOne(
        state: CosmicMischiefState,
        action: CosmicAction,
        actor: CosmicPlayer = CosmicPlayer.SHAI,
    ): CosmicMischiefState {
        val result = CosmicMischiefEngine.apply(
            CosmicMischiefSession(state),
            CosmicCommand(actor, state.revision, action),
        )
        assertTrue(result.changed)
        return result.session.game
    }

    private fun rigState(
        shaiIds: List<Int>,
        rivenIds: List<Int>,
        topId: Int,
        turn: CosmicPlayer = CosmicPlayer.SHAI,
        grudges: List<Int> = listOf(0, 0),
    ): CosmicMischiefState {
        val placed = (shaiIds + rivenIds + topId).toSet()
        require(placed.size == shaiIds.size + rivenIds.size + 1)
        val top = requireNotNull(CosmicCard.fromId(topId))
        return CosmicMischiefState(
            drawPile = CosmicCard.fullDeck.filterNot { it.id in placed },
            discardPile = listOf(top),
            hands = listOf(
                shaiIds.map { requireNotNull(CosmicCard.fromId(it)) },
                rivenIds.map { requireNotNull(CosmicCard.fromId(it)) },
            ),
            activeColor = requireNotNull(top.color),
            turn = turn,
            dealSeed = 77L,
            randomState = 91L,
            grudges = grudges,
        ).also { require(CosmicMischiefEngine.isValid(it)) }
    }

    private fun exhaustedState(
        shaiIds: List<Int>,
        discardIds: List<Int>,
        turn: CosmicPlayer,
    ): CosmicMischiefState {
        val placed = (shaiIds + discardIds).toSet()
        require(placed.size == shaiIds.size + discardIds.size)
        val discard = discardIds.map { requireNotNull(CosmicCard.fromId(it)) }
        val riven = CosmicCard.fullDeck.filterNot { it.id in placed }
        return CosmicMischiefState(
            drawPile = emptyList(),
            discardPile = discard,
            hands = listOf(
                shaiIds.map { requireNotNull(CosmicCard.fromId(it)) },
                riven,
            ),
            activeColor = requireNotNull(discard.last().color),
            turn = turn,
            dealSeed = 101L,
            randomState = 202L,
        ).also { require(CosmicMischiefEngine.isValid(it)) }
    }

    private fun assertMutationRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: UnsupportedOperationException) {
            rejected = true
        }
        assertTrue("Observation collection must be immutable", rejected)
    }

    private fun assertConserved(state: CosmicMischiefState) {
        assertTrue(CosmicMischiefEngine.isValid(state))
        val all = state.drawPile + state.discardPile + state.hands.flatten()
        assertEquals(COSMIC_CARD_COUNT, all.size)
        assertEquals((0 until COSMIC_CARD_COUNT).toList(), all.map(CosmicCard::id).sorted())
    }
}
