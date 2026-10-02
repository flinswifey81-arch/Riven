package com.shai.riven.ui.arcade.solitaire

const val SOLITAIRE_FOUNDATION_COUNT = 4
const val SOLITAIRE_TABLEAU_COUNT = 7
const val SOLITAIRE_CARD_COUNT = 52
const val SOLITAIRE_MAX_UNDO = 50

enum class SolitaireColor {
    RED,
    BLACK,
}

enum class SolitaireSuit(
    val symbol: String,
    val color: SolitaireColor,
) {
    CLUBS("\u2663", SolitaireColor.BLACK),
    DIAMONDS("\u2666", SolitaireColor.RED),
    HEARTS("\u2665", SolitaireColor.RED),
    SPADES("\u2660", SolitaireColor.BLACK),
}

data class SolitaireCard(
    val suit: SolitaireSuit,
    val rank: Int,
) {
    init {
        require(rank in 1..13)
    }

    val id: Int
        get() = suit.ordinal * 13 + rank - 1

    val rankLabel: String
        get() = when (rank) {
            1 -> "A"
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            else -> rank.toString()
        }

    val spokenName: String
        get() = "${rankName(rank)} of ${suit.name.lowercase()}"

    companion object {
        fun fromId(id: Int): SolitaireCard? = if (id in 0 until SOLITAIRE_CARD_COUNT) {
            SolitaireCard(
                suit = SolitaireSuit.entries[id / 13],
                rank = id % 13 + 1,
            )
        } else {
            null
        }

        private fun rankName(rank: Int): String = when (rank) {
            1 -> "ace"
            11 -> "jack"
            12 -> "queen"
            13 -> "king"
            else -> rank.toString()
        }
    }
}

data class SolitaireTableauCard(
    val card: SolitaireCard,
    val faceUp: Boolean,
)

enum class SolitaireStatus {
    PLAYING,
    WON,
}

data class MidnightSolitaireState(
    val stock: List<SolitaireCard>,
    val waste: List<SolitaireCard>,
    val foundations: List<List<SolitaireCard>>,
    val tableau: List<List<SolitaireTableauCard>>,
    val dealSeed: Long,
    val dealNumber: Int = 1,
    val moves: Int = 0,
    val recycles: Int = 0,
    val status: SolitaireStatus = SolitaireStatus.PLAYING,
)

data class MidnightSolitaireSession(
    val game: MidnightSolitaireState,
    val undoStack: List<MidnightSolitaireState> = emptyList(),
)

sealed interface SolitaireMove {
    data object DrawOrRecycle : SolitaireMove
    data object WasteToFoundation : SolitaireMove
    data class WasteToTableau(val toColumn: Int) : SolitaireMove
    data class TableauToFoundation(val fromColumn: Int) : SolitaireMove
    data class TableauToTableau(
        val fromColumn: Int,
        val fromIndex: Int,
        val toColumn: Int,
    ) : SolitaireMove

    data class FoundationToTableau(
        val suit: SolitaireSuit,
        val toColumn: Int,
    ) : SolitaireMove
}

data class SolitaireMoveResult(
    val session: MidnightSolitaireSession,
    val changed: Boolean,
    val message: String,
)

object MidnightSolitaireEngine {
    private const val DEFAULT_RANDOM_STATE = 0x4D595DF4D0F33173L

    fun newGame(
        seed: Long,
        dealNumber: Int = 1,
    ): MidnightSolitaireState {
        val deck = SolitaireSuit.entries
            .flatMap { suit -> (1..13).map { rank -> SolitaireCard(suit, rank) } }
            .toMutableList()
        var randomState = normalizeSeed(seed)
        for (index in deck.lastIndex downTo 1) {
            randomState = nextRandom(randomState)
            val swapIndex = ((randomState ushr 1) % (index + 1)).toInt()
            val value = deck[index]
            deck[index] = deck[swapIndex]
            deck[swapIndex] = value
        }

        val tableau = MutableList(SOLITAIRE_TABLEAU_COUNT) { mutableListOf<SolitaireTableauCard>() }
        for (column in 0 until SOLITAIRE_TABLEAU_COUNT) {
            repeat(column + 1) { row ->
                tableau[column] += SolitaireTableauCard(
                    card = deck.removeAt(deck.lastIndex),
                    faceUp = row == column,
                )
            }
        }
        return MidnightSolitaireState(
            stock = deck.toList(),
            waste = emptyList(),
            foundations = List(SOLITAIRE_FOUNDATION_COUNT) { emptyList() },
            tableau = tableau.map { it.toList() },
            dealSeed = normalizeSeed(seed),
            dealNumber = dealNumber.coerceAtLeast(1),
        ).also { require(isValid(it)) }
    }

    fun apply(
        session: MidnightSolitaireSession,
        move: SolitaireMove,
    ): SolitaireMoveResult {
        val before = session.game
        if (before.status == SolitaireStatus.WON) {
            return SolitaireMoveResult(session, changed = false, message = "This deal is already complete.")
        }
        val moved = when (move) {
            SolitaireMove.DrawOrRecycle -> drawOrRecycle(before)
            SolitaireMove.WasteToFoundation -> wasteToFoundation(before)
            is SolitaireMove.WasteToTableau -> wasteToTableau(before, move.toColumn)
            is SolitaireMove.TableauToFoundation -> tableauToFoundation(before, move.fromColumn)
            is SolitaireMove.TableauToTableau -> tableauToTableau(
                before,
                move.fromColumn,
                move.fromIndex,
                move.toColumn,
            )

            is SolitaireMove.FoundationToTableau -> foundationToTableau(before, move.suit, move.toColumn)
        }
        if (moved == null) {
            return SolitaireMoveResult(session, changed = false, message = "That is not a legal Klondike move.")
        }
        val completed = moved.copy(
            moves = before.moves + 1,
            status = if (moved.foundations.sumOf { it.size } == SOLITAIRE_CARD_COUNT) {
                SolitaireStatus.WON
            } else {
                SolitaireStatus.PLAYING
            },
        )
        require(isValid(completed))
        val history = (session.undoStack + before).takeLast(SOLITAIRE_MAX_UNDO)
        return SolitaireMoveResult(
            session = MidnightSolitaireSession(completed, history),
            changed = true,
            message = if (completed.status == SolitaireStatus.WON) {
                "Midnight Solitaire complete."
            } else {
                "Move made."
            },
        )
    }

    fun undo(session: MidnightSolitaireSession): SolitaireMoveResult {
        val previous = session.undoStack.lastOrNull()
            ?: return SolitaireMoveResult(session, changed = false, message = "Nothing to undo yet.")
        return SolitaireMoveResult(
            session = MidnightSolitaireSession(
                game = previous,
                undoStack = session.undoStack.dropLast(1),
            ),
            changed = true,
            message = "Move undone.",
        )
    }

    fun freshDeal(
        session: MidnightSolitaireSession,
        seed: Long,
    ): MidnightSolitaireSession = MidnightSolitaireSession(
        game = newGame(seed = seed, dealNumber = session.game.dealNumber + 1),
    )

    fun restart(session: MidnightSolitaireSession): MidnightSolitaireSession = MidnightSolitaireSession(
        game = newGame(
            seed = session.game.dealSeed,
            dealNumber = session.game.dealNumber,
        ),
    )

    fun legalMoves(state: MidnightSolitaireState): Set<SolitaireMove> {
        if (state.status == SolitaireStatus.WON) return emptySet()
        return buildSet {
            if (state.stock.isNotEmpty() || state.waste.isNotEmpty()) add(SolitaireMove.DrawOrRecycle)
            if (wasteToFoundation(state) != null) add(SolitaireMove.WasteToFoundation)
            for (toColumn in 0 until SOLITAIRE_TABLEAU_COUNT) {
                if (wasteToTableau(state, toColumn) != null) add(SolitaireMove.WasteToTableau(toColumn))
            }
            for (fromColumn in 0 until SOLITAIRE_TABLEAU_COUNT) {
                if (tableauToFoundation(state, fromColumn) != null) {
                    add(SolitaireMove.TableauToFoundation(fromColumn))
                }
                state.tableau[fromColumn].indices.forEach { fromIndex ->
                    for (toColumn in 0 until SOLITAIRE_TABLEAU_COUNT) {
                        if (tableauToTableau(state, fromColumn, fromIndex, toColumn) != null) {
                            add(SolitaireMove.TableauToTableau(fromColumn, fromIndex, toColumn))
                        }
                    }
                }
            }
            SolitaireSuit.entries.forEach { suit ->
                for (toColumn in 0 until SOLITAIRE_TABLEAU_COUNT) {
                    if (foundationToTableau(state, suit, toColumn) != null) {
                        add(SolitaireMove.FoundationToTableau(suit, toColumn))
                    }
                }
            }
        }
    }

    fun allCards(state: MidnightSolitaireState): List<SolitaireCard> = buildList {
        addAll(state.stock)
        addAll(state.waste)
        state.foundations.forEach(::addAll)
        state.tableau.forEach { pile -> pile.forEach { add(it.card) } }
    }

    fun isValid(state: MidnightSolitaireState): Boolean {
        if (state.foundations.size != SOLITAIRE_FOUNDATION_COUNT) return false
        if (state.tableau.size != SOLITAIRE_TABLEAU_COUNT) return false
        if (state.dealNumber < 1 || state.moves < 0 || state.recycles < 0) return false
        val cards = allCards(state)
        if (cards.size != SOLITAIRE_CARD_COUNT || cards.map(SolitaireCard::id).toSet().size != SOLITAIRE_CARD_COUNT) {
            return false
        }
        state.foundations.forEachIndexed { index, pile ->
            if (pile.any { it.suit.ordinal != index }) return false
            if (pile.map(SolitaireCard::rank) != (1..pile.size).toList()) return false
        }
        state.tableau.forEach { pile ->
            val firstFaceUp = pile.indexOfFirst(SolitaireTableauCard::faceUp)
            if (pile.isNotEmpty() && firstFaceUp < 0) return false
            if (firstFaceUp >= 0) {
                if (pile.drop(firstFaceUp).any { !it.faceUp }) return false
                if (!isValidTableauSequence(pile, firstFaceUp)) return false
            }
        }
        val won = state.foundations.sumOf { it.size } == SOLITAIRE_CARD_COUNT
        return (state.status == SolitaireStatus.WON) == won
    }

    private fun drawOrRecycle(state: MidnightSolitaireState): MidnightSolitaireState? = when {
        state.stock.isNotEmpty() -> state.copy(
            stock = state.stock.dropLast(1),
            waste = state.waste + state.stock.last(),
        )

        state.waste.isNotEmpty() -> state.copy(
            stock = state.waste.reversed(),
            waste = emptyList(),
            recycles = state.recycles + 1,
        )

        else -> null
    }

    private fun wasteToFoundation(state: MidnightSolitaireState): MidnightSolitaireState? {
        val card = state.waste.lastOrNull() ?: return null
        val foundationIndex = card.suit.ordinal
        val foundation = state.foundations[foundationIndex]
        if (!canMoveToFoundation(card, foundation)) return null
        return state.copy(
            waste = state.waste.dropLast(1),
            foundations = state.foundations.replaced(foundationIndex, foundation + card),
        )
    }

    private fun wasteToTableau(
        state: MidnightSolitaireState,
        toColumn: Int,
    ): MidnightSolitaireState? {
        if (toColumn !in state.tableau.indices) return null
        val card = state.waste.lastOrNull() ?: return null
        if (!canMoveToTableau(card, state.tableau[toColumn])) return null
        return state.copy(
            waste = state.waste.dropLast(1),
            tableau = state.tableau.replaced(
                toColumn,
                state.tableau[toColumn] + SolitaireTableauCard(card, faceUp = true),
            ),
        )
    }

    private fun tableauToFoundation(
        state: MidnightSolitaireState,
        fromColumn: Int,
    ): MidnightSolitaireState? {
        if (fromColumn !in state.tableau.indices) return null
        val source = state.tableau[fromColumn]
        val card = source.lastOrNull()?.takeIf(SolitaireTableauCard::faceUp)?.card ?: return null
        val foundationIndex = card.suit.ordinal
        val foundation = state.foundations[foundationIndex]
        if (!canMoveToFoundation(card, foundation)) return null
        return state.copy(
            foundations = state.foundations.replaced(foundationIndex, foundation + card),
            tableau = state.tableau.replaced(fromColumn, removeAndReveal(source, source.lastIndex)),
        )
    }

    private fun tableauToTableau(
        state: MidnightSolitaireState,
        fromColumn: Int,
        fromIndex: Int,
        toColumn: Int,
    ): MidnightSolitaireState? {
        if (fromColumn !in state.tableau.indices || toColumn !in state.tableau.indices) return null
        if (fromColumn == toColumn) return null
        val source = state.tableau[fromColumn]
        if (fromIndex !in source.indices || !source[fromIndex].faceUp) return null
        if (!isValidTableauSequence(source, fromIndex)) return null
        val moving = source.drop(fromIndex)
        if (!canMoveToTableau(moving.first().card, state.tableau[toColumn])) return null
        return state.copy(
            tableau = state.tableau
                .replaced(fromColumn, removeAndReveal(source, fromIndex))
                .replaced(toColumn, state.tableau[toColumn] + moving),
        )
    }

    private fun foundationToTableau(
        state: MidnightSolitaireState,
        suit: SolitaireSuit,
        toColumn: Int,
    ): MidnightSolitaireState? {
        if (toColumn !in state.tableau.indices) return null
        val foundation = state.foundations[suit.ordinal]
        val card = foundation.lastOrNull() ?: return null
        if (!canMoveToTableau(card, state.tableau[toColumn])) return null
        return state.copy(
            foundations = state.foundations.replaced(suit.ordinal, foundation.dropLast(1)),
            tableau = state.tableau.replaced(
                toColumn,
                state.tableau[toColumn] + SolitaireTableauCard(card, faceUp = true),
            ),
        )
    }

    private fun canMoveToFoundation(
        card: SolitaireCard,
        foundation: List<SolitaireCard>,
    ): Boolean = foundation.lastOrNull()?.let { top ->
        top.suit == card.suit && card.rank == top.rank + 1
    } ?: (card.rank == 1)

    private fun canMoveToTableau(
        card: SolitaireCard,
        tableau: List<SolitaireTableauCard>,
    ): Boolean = tableau.lastOrNull()?.takeIf(SolitaireTableauCard::faceUp)?.card?.let { top ->
        top.suit.color != card.suit.color && top.rank == card.rank + 1
    } ?: (tableau.isEmpty() && card.rank == 13)

    private fun isValidTableauSequence(
        pile: List<SolitaireTableauCard>,
        startIndex: Int,
    ): Boolean = pile.drop(startIndex).zipWithNext().all { (upper, lower) ->
        upper.faceUp && lower.faceUp &&
            upper.card.suit.color != lower.card.suit.color &&
            upper.card.rank == lower.card.rank + 1
    }

    private fun removeAndReveal(
        pile: List<SolitaireTableauCard>,
        fromIndex: Int,
    ): List<SolitaireTableauCard> {
        val remaining = pile.take(fromIndex).toMutableList()
        if (remaining.lastOrNull()?.faceUp == false) {
            remaining[remaining.lastIndex] = remaining.last().copy(faceUp = true)
        }
        return remaining
    }

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also {
        it[index] = value
    }

    private fun normalizeSeed(seed: Long): Long = if (seed == 0L) DEFAULT_RANDOM_STATE else seed

    private fun nextRandom(state: Long): Long {
        var value = normalizeSeed(state)
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return value
    }
}
