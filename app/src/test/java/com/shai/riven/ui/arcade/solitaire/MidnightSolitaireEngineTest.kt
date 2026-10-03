package com.shai.riven.ui.arcade.solitaire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidnightSolitaireEngineTest {
    @Test
    fun deterministicDealHasStandardShapeAndConservesEveryCard() {
        val first = MidnightSolitaireEngine.newGame(seed = 42L)
        val second = MidnightSolitaireEngine.newGame(seed = 42L)

        assertEquals(first, second)
        assertEquals(24, first.stock.size)
        assertEquals((1..7).toList(), first.tableau.map { it.size })
        first.tableau.forEach { pile ->
            assertTrue(pile.last().faceUp)
            assertTrue(pile.dropLast(1).none(SolitaireTableauCard::faceUp))
        }
        assertConserved(first)
        assertTrue(MidnightSolitaireEngine.isValid(first))
    }

    @Test
    fun legalMovesFollowFoundationAndAlternatingDescendingTableauRules() {
        val heartAce = card(SolitaireSuit.HEARTS, 1)
        val heartTwo = card(SolitaireSuit.HEARTS, 2)
        val clubThree = card(SolitaireSuit.CLUBS, 3)
        val diamondThree = card(SolitaireSuit.DIAMONDS, 3)
        val state = fixture(
            waste = listOf(heartTwo),
            foundations = foundations(hearts = listOf(heartAce)),
            tableau = tableau(
                listOf(up(clubThree)),
                listOf(up(diamondThree)),
            ),
        )

        val legal = MidnightSolitaireEngine.legalMoves(state)

        assertTrue(SolitaireMove.WasteToFoundation(SolitaireSuit.HEARTS) in legal)
        assertTrue(SolitaireMove.WasteToTableau(0) in legal)
        assertFalse(SolitaireMove.WasteToTableau(1) in legal)
        val foundationMove = MidnightSolitaireEngine.apply(
            MidnightSolitaireSession(state),
            SolitaireMove.WasteToFoundation(SolitaireSuit.HEARTS),
        )
        assertTrue(foundationMove.changed)
        assertEquals(listOf(heartAce, heartTwo), foundationMove.session.game.foundations[SolitaireSuit.HEARTS.ordinal])
        assertConserved(foundationMove.session.game)
    }

    @Test
    fun movingATableauSequenceRevealsTheNewTopCard() {
        val hidden = card(SolitaireSuit.SPADES, 5)
        val redQueen = card(SolitaireSuit.HEARTS, 12)
        val blackJack = card(SolitaireSuit.CLUBS, 11)
        val blackKing = card(SolitaireSuit.SPADES, 13)
        val state = fixture(
            tableau = tableau(
                listOf(
                    SolitaireTableauCard(hidden, faceUp = false),
                    up(redQueen),
                    up(blackJack),
                ),
                listOf(up(blackKing)),
            ),
        )

        val result = MidnightSolitaireEngine.apply(
            MidnightSolitaireSession(state),
            SolitaireMove.TableauToTableau(fromColumn = 0, fromIndex = 1, toColumn = 1),
        )

        assertTrue(result.changed)
        assertEquals(listOf(up(hidden)), result.session.game.tableau[0])
        assertEquals(listOf(up(blackKing), up(redQueen), up(blackJack)), result.session.game.tableau[1])
        assertConserved(result.session.game)
    }

    @Test
    fun foundationMoveRequiresTheSelectedTopCardAndMatchingTargetPile() {
        val buriedHeartTwo = card(SolitaireSuit.HEARTS, 2)
        val topClubAce = card(SolitaireSuit.CLUBS, 1)
        val state = fixture(
            tableau = tableau(
                listOf(up(buriedHeartTwo), up(topClubAce)),
            ),
        )
        val session = MidnightSolitaireSession(state)

        val buried = MidnightSolitaireEngine.apply(
            session,
            SolitaireMove.TableauToFoundation(
                fromColumn = 0,
                fromIndex = 0,
                targetSuit = SolitaireSuit.HEARTS,
            ),
        )
        val wrongSuit = MidnightSolitaireEngine.apply(
            session,
            SolitaireMove.TableauToFoundation(
                fromColumn = 0,
                fromIndex = 1,
                targetSuit = SolitaireSuit.HEARTS,
            ),
        )
        val valid = MidnightSolitaireEngine.apply(
            session,
            SolitaireMove.TableauToFoundation(
                fromColumn = 0,
                fromIndex = 1,
                targetSuit = SolitaireSuit.CLUBS,
            ),
        )

        assertFalse(buried.changed)
        assertEquals(state, buried.session.game)
        assertFalse(wrongSuit.changed)
        assertEquals(state, wrongSuit.session.game)
        assertTrue(valid.changed)
        assertEquals(listOf(topClubAce), valid.session.game.foundations[SolitaireSuit.CLUBS.ordinal])
        assertEquals(listOf(up(buriedHeartTwo)), valid.session.game.tableau[0])
        assertConserved(valid.session.game)
    }

    @Test
    fun undoRestoresTheExactPriorPosition() {
        val original = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 9001L))
        val moved = MidnightSolitaireEngine.apply(original, SolitaireMove.DrawOrRecycle)

        val undone = MidnightSolitaireEngine.undo(moved.session)

        assertTrue(moved.changed)
        assertTrue(undone.changed)
        assertEquals(original, undone.session)
        assertConserved(undone.session.game)
    }

    @Test
    fun drawOneRecycleIsUnlimitedAndRestoresStockOrder() {
        val original = MidnightSolitaireEngine.newGame(seed = 77L)
        var session = MidnightSolitaireSession(original)
        repeat(original.stock.size) {
            session = MidnightSolitaireEngine.apply(session, SolitaireMove.DrawOrRecycle).session
        }
        assertTrue(session.game.stock.isEmpty())
        assertEquals(original.stock.reversed(), session.game.waste)

        session = MidnightSolitaireEngine.apply(session, SolitaireMove.DrawOrRecycle).session

        assertEquals(original.stock, session.game.stock)
        assertTrue(session.game.waste.isEmpty())
        assertEquals(1, session.game.recycles)
        assertConserved(session.game)
        assertTrue(SolitaireMove.DrawOrRecycle in MidnightSolitaireEngine.legalMoves(session.game))
    }

    @Test
    fun finalFoundationMoveProducesAWinWithoutAnyLossState() {
        val spadeKing = card(SolitaireSuit.SPADES, 13)
        val state = MidnightSolitaireState(
            stock = emptyList(),
            waste = emptyList(),
            foundations = SolitaireSuit.entries.map { suit ->
                val finalRank = if (suit == SolitaireSuit.SPADES) 12 else 13
                (1..finalRank).map { rank -> card(suit, rank) }
            },
            tableau = tableau(listOf(up(spadeKing))),
            dealSeed = 5L,
        )
        assertTrue(MidnightSolitaireEngine.isValid(state))

        val result = MidnightSolitaireEngine.apply(
            MidnightSolitaireSession(state),
            SolitaireMove.TableauToFoundation(
                fromColumn = 0,
                fromIndex = 0,
                targetSuit = SolitaireSuit.SPADES,
            ),
        )

        assertEquals(SolitaireStatus.WON, result.session.game.status)
        assertTrue(MidnightSolitaireEngine.legalMoves(result.session.game).isEmpty())
        assertEquals(listOf(SolitaireStatus.PLAYING, SolitaireStatus.WON), SolitaireStatus.entries)
        assertConserved(result.session.game)
    }

    @Test
    fun freshDealChangesSeedAndRestartRebuildsThatExactDeal() {
        val initial = MidnightSolitaireSession(MidnightSolitaireEngine.newGame(seed = 100L))
        val progressed = MidnightSolitaireEngine.apply(initial, SolitaireMove.DrawOrRecycle).session

        val fresh = MidnightSolitaireEngine.freshDeal(progressed, seed = 200L)
        val restarted = MidnightSolitaireEngine.restart(
            MidnightSolitaireEngine.apply(fresh, SolitaireMove.DrawOrRecycle).session,
        )

        assertNotEquals(initial.game, fresh.game)
        assertEquals(2, fresh.game.dealNumber)
        assertEquals(MidnightSolitaireEngine.newGame(seed = 200L, dealNumber = 2), restarted.game)
        assertTrue(fresh.undoStack.isEmpty())
        assertTrue(restarted.undoStack.isEmpty())
        assertEquals(SolitaireStatus.PLAYING, fresh.game.status)
        assertConserved(fresh.game)
        assertConserved(restarted.game)
    }

    private fun fixture(
        waste: List<SolitaireCard> = emptyList(),
        foundations: List<List<SolitaireCard>> = foundations(),
        tableau: List<List<SolitaireTableauCard>> = tableau(),
    ): MidnightSolitaireState {
        val placed = buildSet {
            addAll(waste)
            foundations.forEach(::addAll)
            tableau.forEach { pile -> pile.forEach { add(it.card) } }
        }
        val stock = SolitaireSuit.entries.flatMap { suit ->
            (1..13).map { rank -> card(suit, rank) }
        }.filterNot(placed::contains)
        return MidnightSolitaireState(
            stock = stock,
            waste = waste,
            foundations = foundations,
            tableau = tableau,
            dealSeed = 123L,
        ).also { assertTrue(MidnightSolitaireEngine.isValid(it)) }
    }

    private fun foundations(
        clubs: List<SolitaireCard> = emptyList(),
        diamonds: List<SolitaireCard> = emptyList(),
        hearts: List<SolitaireCard> = emptyList(),
        spades: List<SolitaireCard> = emptyList(),
    ): List<List<SolitaireCard>> = listOf(clubs, diamonds, hearts, spades)

    private fun tableau(vararg piles: List<SolitaireTableauCard>): List<List<SolitaireTableauCard>> =
        piles.toList() + List(SOLITAIRE_TABLEAU_COUNT - piles.size) { emptyList() }

    private fun card(suit: SolitaireSuit, rank: Int) = SolitaireCard(suit, rank)

    private fun up(card: SolitaireCard) = SolitaireTableauCard(card, faceUp = true)

    private fun assertConserved(state: MidnightSolitaireState) {
        val cards = MidnightSolitaireEngine.allCards(state)
        assertEquals(SOLITAIRE_CARD_COUNT, cards.size)
        assertEquals((0 until SOLITAIRE_CARD_COUNT).toSet(), cards.map(SolitaireCard::id).toSet())
    }
}
