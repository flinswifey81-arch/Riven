package com.shai.riven.ui.arcade.cosmic

const val COSMIC_CARD_COUNT = 52
const val COSMIC_STARTING_HAND_SIZE = 7
const val COSMIC_CAUGHT_CHEAT_PENALTY = 2
const val COSMIC_FALSE_CALLOUT_PENALTY = 1
const val COSMIC_EVENT_LIMIT = 24

enum class CosmicPlayer(val displayName: String) {
    SHAI("Shai"),
    RIVEN("Offline rival"),
    ;

    val other: CosmicPlayer
        get() = if (this == SHAI) RIVEN else SHAI
}

enum class CosmicColor(val label: String, val symbol: String) {
    RUBY("Ruby", "R"),
    AQUA("Aqua", "A"),
    VIOLET("Violet", "V"),
    LIME("Lime", "L"),
}

sealed interface CosmicCardKind {
    data class Number(val value: Int) : CosmicCardKind
    data object Eclipse : CosmicCardKind
    data object DoubleTrouble : CosmicCardKind
    data object RewriteTheStars : CosmicCardKind
}

data class CosmicCard(
    val id: Int,
    val color: CosmicColor?,
    val kind: CosmicCardKind,
) {
    val shortLabel: String
        get() = when (kind) {
            is CosmicCardKind.Number -> kind.value.toString()
            CosmicCardKind.Eclipse -> "E"
            CosmicCardKind.DoubleTrouble -> "+2"
            CosmicCardKind.RewriteTheStars -> "*"
        }

    val spokenName: String
        get() = when (kind) {
            is CosmicCardKind.Number -> "${requireNotNull(color).label} ${kind.value}"
            CosmicCardKind.Eclipse -> "${requireNotNull(color).label} Eclipse"
            CosmicCardKind.DoubleTrouble -> "${requireNotNull(color).label} Double Trouble"
            CosmicCardKind.RewriteTheStars -> "Rewrite the Stars"
        }

    companion object {
        val fullDeck: List<CosmicCard> by lazy {
            buildList {
                CosmicColor.entries.forEach { color ->
                    (0..9).forEach { value ->
                        add(CosmicCard(size, color, CosmicCardKind.Number(value)))
                    }
                }
                CosmicColor.entries.forEach { color ->
                    add(CosmicCard(size, color, CosmicCardKind.Eclipse))
                }
                CosmicColor.entries.forEach { color ->
                    add(CosmicCard(size, color, CosmicCardKind.DoubleTrouble))
                }
                repeat(4) {
                    add(CosmicCard(size, null, CosmicCardKind.RewriteTheStars))
                }
            }.also { deck ->
                require(deck.size == COSMIC_CARD_COUNT)
                require(deck.map(CosmicCard::id).toSet().size == COSMIC_CARD_COUNT)
            }
        }

        fun fromId(id: Int): CosmicCard? = fullDeck.getOrNull(id)?.takeIf { it.id == id }
    }
}

enum class CosmicStatus {
    PLAYING,
    SHAI_WON,
    RIVEN_WON,
}

enum class CosmicEventType {
    DEAL_STARTED,
    CARD_PLAYED,
    CARD_DRAWN,
    ECLIPSE_PLAYED,
    DOUBLE_TROUBLE_PLAYED,
    STARS_REWRITTEN,
    CHEAT_TELL,
    CHEAT_CAUGHT,
    CHEAT_ESCAPED,
    FALSE_CALLOUT,
    TRADE_OFFERED,
    TRADE_REFUSED,
    BARGAIN_HONORED,
    BARGAIN_BETRAYED,
    GAME_WON,
}

data class CosmicEvent(
    val type: CosmicEventType,
    val actor: CosmicPlayer? = null,
    val target: CosmicPlayer? = null,
    val cardId: Int? = null,
    val secondaryCardId: Int? = null,
    val message: String,
)

data class CosmicCheatRecord(
    val accused: CosmicPlayer,
    val primaryCardId: Int,
    val extraCardId: Int,
    val primaryChosenColor: CosmicColor?,
    val extraChosenColor: CosmicColor?,
)

data class CosmicTradeOffer(
    val proposer: CosmicPlayer,
    val offeredCardId: Int,
) {
    val responder: CosmicPlayer
        get() = proposer.other
}

data class CosmicMischiefState(
    val drawPile: List<CosmicCard>,
    val discardPile: List<CosmicCard>,
    val hands: List<List<CosmicCard>>,
    val activeColor: CosmicColor,
    val turn: CosmicPlayer,
    val status: CosmicStatus = CosmicStatus.PLAYING,
    val pendingCheat: CosmicCheatRecord? = null,
    val pendingTrade: CosmicTradeOffer? = null,
    val tradeUsedThisTurn: Boolean = false,
    val grudges: List<Int> = listOf(0, 0),
    val honoredBargains: Int = 0,
    val betrayedBargains: Int = 0,
    val dealSeed: Long,
    val gameNumber: Int = 1,
    val randomState: Long,
    val revision: Long = 0L,
    val moves: Int = 0,
    val events: List<CosmicEvent> = emptyList(),
) {
    fun hand(player: CosmicPlayer): List<CosmicCard> = hands[player.ordinal]

    val topDiscard: CosmicCard
        get() = discardPile.last()
}

data class CosmicMischiefSession(val game: CosmicMischiefState)

enum class CosmicTradeDecision {
    HONOR,
    BETRAY,
    REFUSE,
}

sealed interface CosmicAction {
    data class PlayCard(
        val cardId: Int,
        val chosenColor: CosmicColor? = null,
    ) : CosmicAction

    data object DrawCard : CosmicAction

    data class SneakExtraDiscard(
        val primaryCardId: Int,
        val extraCardId: Int,
        val primaryChosenColor: CosmicColor? = null,
        val extraChosenColor: CosmicColor? = null,
    ) : CosmicAction

    data object CallOut : CosmicAction
    data object DeclineCallout : CosmicAction

    data class OfferTrade(val offeredCardId: Int) : CosmicAction

    data class RespondToTrade(
        val decision: CosmicTradeDecision,
        val returnedCardId: Int? = null,
    ) : CosmicAction
}

data class CosmicCommand(
    val actor: CosmicPlayer,
    val expectedRevision: Long,
    val action: CosmicAction,
)

data class CosmicActionResult(
    val session: CosmicMischiefSession,
    val changed: Boolean,
    val message: String,
    val events: List<CosmicEvent> = emptyList(),
)

data class CosmicTellObservation(
    val accused: CosmicPlayer,
    val message: String,
)

data class CosmicPublicTradeOffer(
    val proposer: CosmicPlayer,
    val responder: CosmicPlayer,
    val offeredCard: CosmicCard,
)

data class CosmicPublicObservation(
    val revision: Long,
    val turn: CosmicPlayer,
    val decisionPlayer: CosmicPlayer,
    val status: CosmicStatus,
    val topDiscard: CosmicCard,
    val activeColor: CosmicColor,
    val drawCount: Int,
    val handCounts: List<Int>,
    val tradeAvailable: Boolean,
    val visibleTell: CosmicTellObservation?,
    val pendingTrade: CosmicPublicTradeOffer?,
    val grudges: List<Int>,
    val recentEvents: List<CosmicEvent>,
)

/** This is the only state supplied to an injected opponent. It never contains the user's hand. */
data class CosmicOpponentObservation(
    val public: CosmicPublicObservation,
    val ownHand: List<CosmicCard>,
)

interface CosmicOpponentAgent {
    val displayName: String

    fun chooseAction(observation: CosmicOpponentObservation): CosmicAction

    fun commentary(
        observation: CosmicPublicObservation,
        event: CosmicEvent,
    ): String?
}

class DeterministicCosmicOpponentAgent : CosmicOpponentAgent {
    override val displayName: String = "Offline rival"

    override fun chooseAction(observation: CosmicOpponentObservation): CosmicAction {
        val public = observation.public
        public.pendingTrade?.takeIf { it.responder == CosmicPlayer.RIVEN }?.let {
            val returned = observation.ownHand.maxByOrNull(CosmicCard::id)
                ?: return CosmicAction.RespondToTrade(CosmicTradeDecision.REFUSE)
            return when {
                public.revision % 7L == 0L -> CosmicAction.RespondToTrade(CosmicTradeDecision.BETRAY)
                public.revision % 3L == 0L -> CosmicAction.RespondToTrade(CosmicTradeDecision.REFUSE)
                else -> CosmicAction.RespondToTrade(
                    decision = CosmicTradeDecision.HONOR,
                    returnedCardId = returned.id,
                )
            }
        }
        public.visibleTell?.takeIf { it.accused == CosmicPlayer.SHAI }?.let {
            return if (public.revision % 4L == 0L) {
                CosmicAction.DeclineCallout
            } else {
                CosmicAction.CallOut
            }
        }
        if (public.tradeAvailable && public.turn == CosmicPlayer.RIVEN && public.revision % 6L == 0L) {
            observation.ownHand.maxByOrNull(CosmicCard::id)?.let { offered ->
                return CosmicAction.OfferTrade(offered.id)
            }
        }
        val playable = observation.ownHand.filter { card ->
            CosmicMischiefEngine.isPlayable(card, public.topDiscard, public.activeColor)
        }.sortedBy(CosmicCard::id)
        if (playable.isNotEmpty()) {
            val primary = playable.first()
            val remaining = observation.ownHand.filterNot { it.id == primary.id }.sortedBy(CosmicCard::id)
            if (remaining.isNotEmpty() && public.revision % 5L == 2L) {
                val extra = remaining.first()
                return CosmicAction.SneakExtraDiscard(
                    primaryCardId = primary.id,
                    extraCardId = extra.id,
                    primaryChosenColor = chosenColor(primary, remaining),
                    extraChosenColor = chosenColor(extra, remaining.filterNot { it.id == extra.id }),
                )
            }
            return CosmicAction.PlayCard(primary.id, chosenColor(primary, remaining))
        }
        return CosmicAction.DrawCard
    }

    override fun commentary(
        observation: CosmicPublicObservation,
        event: CosmicEvent,
    ): String = when (event.type) {
        CosmicEventType.CHEAT_TELL -> "A card moved twice. Entirely ordinary cosmic weather."
        CosmicEventType.CHEAT_CAUGHT -> "Fine. The stars have receipts."
        CosmicEventType.CHEAT_ESCAPED -> "You saw nothing, and the constellation agrees."
        CosmicEventType.BARGAIN_HONORED -> "A perfectly respectable bargain. For once."
        CosmicEventType.BARGAIN_BETRAYED -> "This grudge is decorative, permanent, and very tasteful."
        CosmicEventType.TRADE_REFUSED -> "No deal. I am protecting the mystery."
        CosmicEventType.GAME_WON -> if (observation.status == CosmicStatus.RIVEN_WON) {
            "The offline rival wins this round. Live Riven is still not connected."
        } else {
            "Shai wins. I will be theatrically devastated offline."
        }
        else -> event.message
    }

    private fun chosenColor(card: CosmicCard, remaining: List<CosmicCard>): CosmicColor? {
        if (card.kind != CosmicCardKind.RewriteTheStars) return null
        return CosmicColor.entries.maxByOrNull { color -> remaining.count { it.color == color } }
            ?: CosmicColor.RUBY
    }
}

object CosmicMischiefEngine {
    fun newGame(
        seed: Long,
        gameNumber: Int = 1,
        grudges: List<Int> = listOf(0, 0),
    ): CosmicMischiefState {
        val normalizedSeed = normalizeSeed(seed)
        val (shuffled, nextRandom) = shuffle(CosmicCard.fullDeck, normalizedSeed)
        val deck = shuffled.toMutableList()
        val hands = MutableList(2) { mutableListOf<CosmicCard>() }
        repeat(COSMIC_STARTING_HAND_SIZE) {
            CosmicPlayer.entries.forEach { player -> hands[player.ordinal] += deck.removeLast() }
        }
        val discardIndex = deck.indexOfLast { it.kind is CosmicCardKind.Number }
        require(discardIndex >= 0)
        val firstDiscard = deck.removeAt(discardIndex)
        return CosmicMischiefState(
            drawPile = deck.toList(),
            discardPile = listOf(firstDiscard),
            hands = hands.map { it.sortedBy(CosmicCard::id) },
            activeColor = requireNotNull(firstDiscard.color),
            turn = CosmicPlayer.SHAI,
            grudges = normalizedGrudges(grudges),
            dealSeed = normalizedSeed,
            gameNumber = gameNumber.coerceAtLeast(1),
            randomState = nextRandom,
            events = listOf(
                CosmicEvent(
                    type = CosmicEventType.DEAL_STARTED,
                    message = "Seven cards each. Shai acts first.",
                ),
            ),
        ).also { require(isValid(it)) }
    }

    fun freshGame(session: CosmicMischiefSession, seed: Long): CosmicMischiefSession = CosmicMischiefSession(
        newGame(
            seed = seed,
            gameNumber = session.game.gameNumber + 1,
            grudges = session.game.grudges,
        ),
    )

    fun apply(
        session: CosmicMischiefSession,
        command: CosmicCommand,
    ): CosmicActionResult {
        val state = session.game
        if (command.expectedRevision != state.revision) {
            return rejected(session, "That action is stale; the table has already changed.")
        }
        if (state.status != CosmicStatus.PLAYING) {
            return rejected(session, "This game is already complete. Start a clearly confirmed fresh game to play again.")
        }
        if (command.actor != decisionPlayer(state)) {
            return rejected(session, "It is not ${command.actor.displayName}'s decision.")
        }
        val result = when {
            state.pendingCheat != null -> resolveCalloutDecision(state, command.actor, command.action)
            state.pendingTrade != null -> resolveTradeDecision(state, command.actor, command.action)
            command.actor != state.turn -> null
            else -> when (val action = command.action) {
                is CosmicAction.PlayCard -> playCard(state, command.actor, action)
                CosmicAction.DrawCard -> drawAndEndTurn(state, command.actor)
                is CosmicAction.SneakExtraDiscard -> sneakExtraDiscard(state, command.actor, action)
                CosmicAction.CallOut -> falseCallout(state, command.actor)
                CosmicAction.DeclineCallout -> null
                is CosmicAction.OfferTrade -> offerTrade(state, command.actor, action)
                is CosmicAction.RespondToTrade -> null
            }
        } ?: return rejected(session, "That action is not legal in the current Cosmic Mischief state.")
        require(isValid(result.first))
        val updated = result.first.copy(revision = state.revision + 1L)
        require(isValid(updated))
        return CosmicActionResult(
            session = CosmicMischiefSession(updated),
            changed = true,
            message = result.second.lastOrNull()?.message ?: "The table changed.",
            events = result.second,
        )
    }

    fun decisionPlayer(state: CosmicMischiefState): CosmicPlayer = when {
        state.pendingCheat != null -> state.pendingCheat.accused.other
        state.pendingTrade != null -> state.pendingTrade.responder
        else -> state.turn
    }

    fun publicObservation(state: CosmicMischiefState): CosmicPublicObservation = CosmicPublicObservation(
        revision = state.revision,
        turn = state.turn,
        decisionPlayer = decisionPlayer(state),
        status = state.status,
        topDiscard = state.topDiscard,
        activeColor = state.activeColor,
        drawCount = state.drawPile.size,
        handCounts = CosmicPlayer.entries.map { state.hand(it).size },
        tradeAvailable = state.pendingCheat == null && state.pendingTrade == null && !state.tradeUsedThisTurn,
        visibleTell = state.pendingCheat?.let { cheat ->
            CosmicTellObservation(
                accused = cheat.accused,
                message = "${cheat.accused.displayName} slid a second card toward the discard. Call it out or let it slide.",
            )
        },
        pendingTrade = state.pendingTrade?.let { offer ->
            CosmicPublicTradeOffer(
                proposer = offer.proposer,
                responder = offer.responder,
                offeredCard = requireNotNull(CosmicCard.fromId(offer.offeredCardId)),
            )
        },
        grudges = state.grudges,
        recentEvents = state.events,
    )

    fun opponentObservation(state: CosmicMischiefState): CosmicOpponentObservation = CosmicOpponentObservation(
        public = publicObservation(state),
        ownHand = state.hand(CosmicPlayer.RIVEN),
    )

    fun isPlayable(
        card: CosmicCard,
        topDiscard: CosmicCard,
        activeColor: CosmicColor,
    ): Boolean {
        if (card.kind == CosmicCardKind.RewriteTheStars) return true
        if (card.color == activeColor) return true
        return when {
            card.kind is CosmicCardKind.Number && topDiscard.kind is CosmicCardKind.Number ->
                card.kind.value == topDiscard.kind.value

            card.kind == CosmicCardKind.Eclipse && topDiscard.kind == CosmicCardKind.Eclipse -> true
            card.kind == CosmicCardKind.DoubleTrouble && topDiscard.kind == CosmicCardKind.DoubleTrouble -> true
            else -> false
        }
    }

    fun isValid(state: CosmicMischiefState): Boolean {
        if (state.hands.size != 2 || state.grudges.size != 2 || state.discardPile.isEmpty()) return false
        if (state.revision < 0L || state.moves < 0 || state.gameNumber < 1) return false
        if (state.hands.any { hand -> hand.distinctBy(CosmicCard::id).size != hand.size }) return false
        val allCards = state.drawPile + state.discardPile + state.hands.flatten()
        if (allCards.size != COSMIC_CARD_COUNT) return false
        if (allCards.map(CosmicCard::id).sorted() != (0 until COSMIC_CARD_COUNT).toList()) return false
        if (state.pendingCheat != null && state.pendingTrade != null) return false
        state.pendingCheat?.let { cheat ->
            if (state.discardPile.size < 2) return false
            if (state.discardPile.last().id != cheat.extraCardId) return false
            if (state.discardPile[state.discardPile.lastIndex - 1].id != cheat.primaryCardId) return false
            if (state.hand(cheat.accused).any { it.id == cheat.primaryCardId || it.id == cheat.extraCardId }) return false
        }
        state.pendingTrade?.let { offer ->
            if (state.hand(offer.proposer).none { it.id == offer.offeredCardId }) return false
            if (state.tradeUsedThisTurn) return false
        }
        val shaiEmpty = state.hand(CosmicPlayer.SHAI).isEmpty()
        val rivenEmpty = state.hand(CosmicPlayer.RIVEN).isEmpty()
        return when (state.status) {
            CosmicStatus.PLAYING -> {
                val pendingEmpty = state.pendingCheat?.accused
                (!shaiEmpty || pendingEmpty == CosmicPlayer.SHAI) &&
                    (!rivenEmpty || pendingEmpty == CosmicPlayer.RIVEN)
            }
            CosmicStatus.SHAI_WON -> shaiEmpty && !rivenEmpty && state.pendingCheat == null
            CosmicStatus.RIVEN_WON -> rivenEmpty && !shaiEmpty && state.pendingCheat == null
        }
    }

    private fun playCard(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        action: CosmicAction.PlayCard,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val card = state.hand(actor).firstOrNull { it.id == action.cardId } ?: return null
        if (!isPlayable(card, state.topDiscard, state.activeColor)) return null
        if (!validChosenColor(card, action.chosenColor)) return null
        val updatedHands = replaceHand(state.hands, actor, state.hand(actor).filterNot { it.id == card.id })
        val played = state.copy(
            hands = updatedHands,
            discardPile = state.discardPile + card,
            activeColor = action.chosenColor ?: requireNotNull(card.color),
            pendingTrade = null,
            tradeUsedThisTurn = false,
            moves = state.moves + 1,
        )
        val event = cardEvent(actor, card, action.chosenColor)
        return finishResolvedPlay(played, actor, card, listOf(event))
    }

    private fun sneakExtraDiscard(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        action: CosmicAction.SneakExtraDiscard,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        if (action.primaryCardId == action.extraCardId) return null
        val hand = state.hand(actor)
        val primary = hand.firstOrNull { it.id == action.primaryCardId } ?: return null
        val extra = hand.firstOrNull { it.id == action.extraCardId } ?: return null
        if (!isPlayable(primary, state.topDiscard, state.activeColor)) return null
        if (!validChosenColor(primary, action.primaryChosenColor)) return null
        if (!validChosenColor(extra, action.extraChosenColor)) return null
        val remaining = hand.filterNot { it.id == primary.id || it.id == extra.id }
        val tell = CosmicEvent(
            type = CosmicEventType.CHEAT_TELL,
            actor = actor,
            target = actor.other,
            cardId = primary.id,
            secondaryCardId = extra.id,
            message = "${actor.displayName} has an observable second-card tell. Call it out or let it slide; there is no timer.",
        )
        val updated = state.copy(
            hands = replaceHand(state.hands, actor, remaining),
            discardPile = state.discardPile + primary + extra,
            activeColor = action.extraChosenColor ?: requireNotNull(extra.color),
            pendingCheat = CosmicCheatRecord(
                accused = actor,
                primaryCardId = primary.id,
                extraCardId = extra.id,
                primaryChosenColor = action.primaryChosenColor,
                extraChosenColor = action.extraChosenColor,
            ),
            pendingTrade = null,
            tradeUsedThisTurn = false,
            moves = state.moves + 1,
            events = appendEvents(state.events, listOf(tell)),
        )
        return updated to listOf(tell)
    }

    private fun resolveCalloutDecision(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        action: CosmicAction,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val cheat = state.pendingCheat ?: return null
        if (actor != cheat.accused.other) return null
        return when (action) {
            CosmicAction.CallOut -> catchCheat(state, actor, cheat)
            CosmicAction.DeclineCallout -> acceptCheat(state, cheat)
            else -> null
        }
    }

    private fun catchCheat(
        state: CosmicMischiefState,
        caller: CosmicPlayer,
        cheat: CosmicCheatRecord,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val extra = CosmicCard.fromId(cheat.extraCardId) ?: return null
        val primary = CosmicCard.fromId(cheat.primaryCardId) ?: return null
        var caught = state.copy(
            discardPile = state.discardPile.dropLast(1),
            hands = replaceHand(
                state.hands,
                cheat.accused,
                (state.hand(cheat.accused) + extra).sortedBy(CosmicCard::id),
            ),
            activeColor = cheat.primaryChosenColor ?: requireNotNull(primary.color),
            pendingCheat = null,
            turn = caller,
            tradeUsedThisTurn = false,
        )
        val penalty = drawCards(caught, cheat.accused, COSMIC_CAUGHT_CHEAT_PENALTY) ?: return null
        caught = penalty.first.copy(turn = caller)
        val event = CosmicEvent(
            type = CosmicEventType.CHEAT_CAUGHT,
            actor = caller,
            target = cheat.accused,
            cardId = extra.id,
            message = "Caught: the extra card returned and ${cheat.accused.displayName} drew $COSMIC_CAUGHT_CHEAT_PENALTY. The primary effect was cancelled.",
        )
        caught = caught.copy(events = appendEvents(caught.events, listOf(event)))
        return caught to listOf(event)
    }

    private fun acceptCheat(
        state: CosmicMischiefState,
        cheat: CosmicCheatRecord,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val primary = CosmicCard.fromId(cheat.primaryCardId) ?: return null
        val escaped = CosmicEvent(
            type = CosmicEventType.CHEAT_ESCAPED,
            actor = cheat.accused,
            target = cheat.accused.other,
            cardId = cheat.extraCardId,
            message = "The tell was allowed to pass. The extra discard stands.",
        )
        val cleared = state.copy(pendingCheat = null)
        return finishResolvedPlay(cleared, cheat.accused, primary, listOf(escaped))
    }

    private fun falseCallout(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val penalty = drawCards(state, actor, COSMIC_FALSE_CALLOUT_PENALTY) ?: return null
        val event = CosmicEvent(
            type = CosmicEventType.FALSE_CALLOUT,
            actor = actor,
            target = actor.other,
            message = "False callout: ${actor.displayName} drew $COSMIC_FALSE_CALLOUT_PENALTY and yielded the turn.",
        )
        val updated = penalty.first.copy(
            turn = actor.other,
            tradeUsedThisTurn = false,
            moves = state.moves + 1,
            events = appendEvents(penalty.first.events, listOf(event)),
        )
        return updated to listOf(event)
    }

    private fun offerTrade(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        action: CosmicAction.OfferTrade,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        if (state.tradeUsedThisTurn) return null
        val offered = state.hand(actor).firstOrNull { it.id == action.offeredCardId } ?: return null
        val event = CosmicEvent(
            type = CosmicEventType.TRADE_OFFERED,
            actor = actor,
            target = actor.other,
            cardId = offered.id,
            message = "${actor.displayName} offered ${offered.spokenName} for one blind chosen return card.",
        )
        return state.copy(
            pendingTrade = CosmicTradeOffer(actor, offered.id),
            events = appendEvents(state.events, listOf(event)),
        ) to listOf(event)
    }

    private fun resolveTradeDecision(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        action: CosmicAction,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val offer = state.pendingTrade ?: return null
        if (actor != offer.responder || action !is CosmicAction.RespondToTrade) return null
        val offered = state.hand(offer.proposer).firstOrNull { it.id == offer.offeredCardId } ?: return null
        return when (action.decision) {
            CosmicTradeDecision.REFUSE -> {
                if (action.returnedCardId != null) return null
                val event = CosmicEvent(
                    type = CosmicEventType.TRADE_REFUSED,
                    actor = actor,
                    target = offer.proposer,
                    cardId = offered.id,
                    message = "${actor.displayName} refused the bargain. The offered card stayed put.",
                )
                state.copy(
                    pendingTrade = null,
                    tradeUsedThisTurn = true,
                    events = appendEvents(state.events, listOf(event)),
                ) to listOf(event)
            }

            CosmicTradeDecision.HONOR -> {
                val returned = state.hand(actor).firstOrNull { it.id == action.returnedCardId } ?: return null
                val proposerHand = state.hand(offer.proposer).filterNot { it.id == offered.id } + returned
                val responderHand = state.hand(actor).filterNot { it.id == returned.id } + offered
                val event = CosmicEvent(
                    type = CosmicEventType.BARGAIN_HONORED,
                    actor = actor,
                    target = offer.proposer,
                    cardId = offered.id,
                    secondaryCardId = returned.id,
                    message = "Bargain honored: ${offered.spokenName} and ${returned.spokenName} changed hands.",
                )
                state.copy(
                    hands = replaceHand(
                        replaceHand(state.hands, offer.proposer, proposerHand.sortedBy(CosmicCard::id)),
                        actor,
                        responderHand.sortedBy(CosmicCard::id),
                    ),
                    pendingTrade = null,
                    tradeUsedThisTurn = true,
                    honoredBargains = state.honoredBargains + 1,
                    events = appendEvents(state.events, listOf(event)),
                ) to listOf(event)
            }

            CosmicTradeDecision.BETRAY -> {
                if (action.returnedCardId != null) return null
                val transferred = state.copy(
                    hands = replaceHand(
                        replaceHand(
                            state.hands,
                            offer.proposer,
                            state.hand(offer.proposer).filterNot { it.id == offered.id },
                        ),
                        actor,
                        (state.hand(actor) + offered).sortedBy(CosmicCard::id),
                    ),
                    pendingTrade = null,
                    tradeUsedThisTurn = true,
                    grudges = state.grudges.mapIndexed { index, value ->
                        if (index == offer.proposer.ordinal) (value + 1).coerceAtMost(99) else value
                    },
                    betrayedBargains = state.betrayedBargains + 1,
                )
                val consolation = drawCards(transferred, offer.proposer, 1) ?: return null
                val event = CosmicEvent(
                    type = CosmicEventType.BARGAIN_BETRAYED,
                    actor = actor,
                    target = offer.proposer,
                    cardId = offered.id,
                    message = "Bargain betrayed: ${actor.displayName} kept ${offered.spokenName}; ${offer.proposer.displayName} drew one consolation card and gained a grudge.",
                )
                consolation.first.copy(
                    events = appendEvents(consolation.first.events, listOf(event)),
                ) to listOf(event)
            }
        }
    }

    private fun drawAndEndTurn(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        val draw = drawCards(state, actor, 1) ?: return null
        val event = CosmicEvent(
            type = CosmicEventType.CARD_DRAWN,
            actor = actor,
            message = "${actor.displayName} drew one card and yielded the turn.",
        )
        return draw.first.copy(
            turn = actor.other,
            tradeUsedThisTurn = false,
            moves = state.moves + 1,
            events = appendEvents(draw.first.events, listOf(event)),
        ) to listOf(event)
    }

    private fun finishResolvedPlay(
        state: CosmicMischiefState,
        actor: CosmicPlayer,
        effectCard: CosmicCard,
        initialEvents: List<CosmicEvent>,
    ): Pair<CosmicMischiefState, List<CosmicEvent>>? {
        if (state.hand(actor).isEmpty()) {
            val winEvent = CosmicEvent(
                type = CosmicEventType.GAME_WON,
                actor = actor,
                target = actor.other,
                message = "${actor.displayName} emptied their hand and won Cosmic Mischief.",
            )
            val allEvents = initialEvents + winEvent
            return state.copy(
                pendingCheat = null,
                pendingTrade = null,
                status = if (actor == CosmicPlayer.SHAI) CosmicStatus.SHAI_WON else CosmicStatus.RIVEN_WON,
                events = appendEvents(state.events, allEvents),
            ) to allEvents
        }
        var resolved = state.copy(pendingCheat = null, pendingTrade = null, tradeUsedThisTurn = false)
        val effectEvents = mutableListOf<CosmicEvent>()
        when (effectCard.kind) {
            is CosmicCardKind.Number -> resolved = resolved.copy(turn = actor.other)
            CosmicCardKind.RewriteTheStars -> {
                resolved = resolved.copy(turn = actor.other)
                effectEvents += CosmicEvent(
                    type = CosmicEventType.STARS_REWRITTEN,
                    actor = actor,
                    target = actor.other,
                    cardId = effectCard.id,
                    message = "${actor.displayName} rewrote the active color to ${resolved.activeColor.label}.",
                )
            }
            CosmicCardKind.Eclipse -> {
                resolved = resolved.copy(turn = actor)
                effectEvents += CosmicEvent(
                    type = CosmicEventType.ECLIPSE_PLAYED,
                    actor = actor,
                    target = actor.other,
                    cardId = effectCard.id,
                    message = "Eclipse skipped ${actor.other.displayName}; ${actor.displayName} acts again.",
                )
            }
            CosmicCardKind.DoubleTrouble -> {
                val penalty = drawCards(resolved, actor.other, 2) ?: return null
                resolved = penalty.first.copy(turn = actor)
                effectEvents += CosmicEvent(
                    type = CosmicEventType.DOUBLE_TROUBLE_PLAYED,
                    actor = actor,
                    target = actor.other,
                    cardId = effectCard.id,
                    message = "Double Trouble made ${actor.other.displayName} draw two and lose the turn.",
                )
            }
        }
        val allEvents = initialEvents + effectEvents
        resolved = resolved.copy(events = appendEvents(state.events, allEvents))
        return resolved to allEvents
    }

    private fun drawCards(
        source: CosmicMischiefState,
        player: CosmicPlayer,
        count: Int,
    ): Pair<CosmicMischiefState, List<CosmicCard>>? {
        var state = source
        val drawn = mutableListOf<CosmicCard>()
        repeat(count) {
            if (state.drawPile.isEmpty()) {
                val protectedCount = if (state.pendingCheat == null) 1 else 2
                if (state.discardPile.size <= protectedCount) return null
                val recyclable = state.discardPile.dropLast(protectedCount)
                val protected = state.discardPile.takeLast(protectedCount)
                val shuffled = shuffle(recyclable, state.randomState)
                state = state.copy(
                    drawPile = shuffled.first,
                    discardPile = protected,
                    randomState = shuffled.second,
                )
            }
            val card = state.drawPile.lastOrNull() ?: return null
            state = state.copy(
                drawPile = state.drawPile.dropLast(1),
                hands = replaceHand(
                    state.hands,
                    player,
                    (state.hand(player) + card).sortedBy(CosmicCard::id),
                ),
            )
            drawn += card
        }
        return state to drawn
    }

    private fun cardEvent(
        actor: CosmicPlayer,
        card: CosmicCard,
        chosenColor: CosmicColor?,
    ): CosmicEvent = CosmicEvent(
        type = CosmicEventType.CARD_PLAYED,
        actor = actor,
        target = actor.other,
        cardId = card.id,
        message = if (card.kind == CosmicCardKind.RewriteTheStars) {
            "${actor.displayName} played ${card.spokenName} and chose ${requireNotNull(chosenColor).label}."
        } else {
            "${actor.displayName} played ${card.spokenName}."
        },
    )

    private fun validChosenColor(card: CosmicCard, chosenColor: CosmicColor?): Boolean =
        if (card.kind == CosmicCardKind.RewriteTheStars) chosenColor != null else chosenColor == null

    private fun replaceHand(
        hands: List<List<CosmicCard>>,
        player: CosmicPlayer,
        replacement: List<CosmicCard>,
    ): List<List<CosmicCard>> = hands.mapIndexed { index, cards ->
        if (index == player.ordinal) replacement else cards
    }

    private fun appendEvents(
        existing: List<CosmicEvent>,
        added: List<CosmicEvent>,
    ): List<CosmicEvent> = (existing + added).takeLast(COSMIC_EVENT_LIMIT)

    private fun rejected(session: CosmicMischiefSession, message: String) = CosmicActionResult(
        session = session,
        changed = false,
        message = message,
    )

    private fun normalizedGrudges(grudges: List<Int>): List<Int> = CosmicPlayer.entries.map { player ->
        grudges.getOrNull(player.ordinal)?.coerceIn(0, 99) ?: 0
    }

    private fun normalizeSeed(seed: Long): Long = seed.takeIf { it != 0L } ?: 0x51A17E5L

    private fun nextRandom(value: Long): Long = value * 6364136223846793005L + 1442695040888963407L

    private fun shuffle(
        cards: List<CosmicCard>,
        seed: Long,
    ): Pair<List<CosmicCard>, Long> {
        val shuffled = cards.toMutableList()
        var random = normalizeSeed(seed)
        for (index in shuffled.lastIndex downTo 1) {
            random = nextRandom(random)
            val target = ((random ushr 1) % (index + 1).toLong()).toInt()
            val card = shuffled[index]
            shuffled[index] = shuffled[target]
            shuffled[target] = card
        }
        return shuffled.toList() to random
    }
}
