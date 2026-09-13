package com.monopoly.core.model

import com.monopoly.core.board.ClassicBoard

/**
 * The standard Chance and Community Chest decks, in their UK wording.
 *
 * Cards are addressed by stable string id rather than by position, so a deck's
 * shuffled order serialises as a list of ids and a saved or resumed game keeps
 * pointing at the right cards even if the text is later reworded.
 */
object ClassicCards {

    val chance: List<Card> = listOf(
        Card("ch_advance_go", "Advance to GO. Collect £200.",
            CardEffect.AdvanceTo(ClassicBoard.GO_INDEX)),
        Card("ch_trafalgar", "Advance to Trafalgar Square. If you pass GO, collect £200.",
            CardEffect.AdvanceTo(24)),
        Card("ch_pall_mall", "Advance to Pall Mall. If you pass GO, collect £200.",
            CardEffect.AdvanceTo(11)),
        Card("ch_nearest_utility", "Advance to the nearest Utility. Pay the owner ten times your dice roll.",
            CardEffect.AdvanceToNearest(CardEffect.AdvanceToNearest.Kind.UTILITY, rentMultiplier = 10)),
        Card("ch_nearest_station_1", "Advance to the nearest Station. Pay the owner twice the usual rent.",
            CardEffect.AdvanceToNearest(CardEffect.AdvanceToNearest.Kind.STATION, rentMultiplier = 2)),
        Card("ch_nearest_station_2", "Advance to the nearest Station. Pay the owner twice the usual rent.",
            CardEffect.AdvanceToNearest(CardEffect.AdvanceToNearest.Kind.STATION, rentMultiplier = 2)),
        Card("ch_dividend", "Bank pays you a dividend of £50.",
            CardEffect.CollectFromBank(50)),
        Card("ch_jail_free", "Get out of Jail Free.",
            CardEffect.GetOutOfJailFree),
        Card("ch_back_three", "Go back three spaces.",
            CardEffect.MoveSpaces(-3)),
        Card("ch_go_to_jail", "Go directly to Jail. Do not pass GO, do not collect £200.",
            CardEffect.GoToJail),
        Card("ch_repairs", "Make general repairs on all your property: £25 per house, £100 per hotel.",
            CardEffect.Repairs(perHouse = 25, perHotel = 100)),
        Card("ch_speeding", "Speeding fine £15.",
            CardEffect.PayBank(15)),
        Card("ch_marylebone", "Take a trip to Marylebone Station. If you pass GO, collect £200.",
            CardEffect.AdvanceTo(15)),
        Card("ch_mayfair", "Take a walk down Mayfair. Advance to Mayfair.",
            CardEffect.AdvanceTo(39, collectSalary = false)),
        Card("ch_chairman", "You have been elected Chairman of the Board. Pay each player £50.",
            CardEffect.PayEachPlayer(50)),
        Card("ch_loan", "Your building loan matures. Collect £150.",
            CardEffect.CollectFromBank(150)),
    )

    val communityChest: List<Card> = listOf(
        Card("cc_advance_go", "Advance to GO. Collect £200.",
            CardEffect.AdvanceTo(ClassicBoard.GO_INDEX)),
        Card("cc_bank_error", "Bank error in your favour. Collect £200.",
            CardEffect.CollectFromBank(200)),
        Card("cc_doctor", "Doctor's fee. Pay £50.",
            CardEffect.PayBank(50)),
        Card("cc_stock", "From sale of stock you get £50.",
            CardEffect.CollectFromBank(50)),
        Card("cc_jail_free", "Get out of Jail Free.",
            CardEffect.GetOutOfJailFree),
        Card("cc_go_to_jail", "Go directly to Jail. Do not pass GO, do not collect £200.",
            CardEffect.GoToJail),
        Card("cc_holiday", "Annual holiday fund matures. Receive £100.",
            CardEffect.CollectFromBank(100)),
        Card("cc_tax_refund", "Income tax refund. Collect £20.",
            CardEffect.CollectFromBank(20)),
        Card("cc_birthday", "It is your birthday. Collect £10 from every player.",
            CardEffect.CollectFromEachPlayer(10)),
        Card("cc_life_insurance", "Life insurance matures. Collect £100.",
            CardEffect.CollectFromBank(100)),
        Card("cc_hospital", "Pay hospital fees of £100.",
            CardEffect.PayBank(100)),
        Card("cc_school", "Pay school fees of £50.",
            CardEffect.PayBank(50)),
        Card("cc_consultancy", "Receive £25 consultancy fee.",
            CardEffect.CollectFromBank(25)),
        Card("cc_street_repairs", "You are assessed for street repairs: £40 per house, £115 per hotel.",
            CardEffect.Repairs(perHouse = 40, perHotel = 115)),
        Card("cc_beauty_contest", "You have won second prize in a beauty contest. Collect £10.",
            CardEffect.CollectFromBank(10)),
        Card("cc_inherit", "You inherit £100.",
            CardEffect.CollectFromBank(100)),
    )

    /** Every card in either deck, indexed by id, for cheap lookup during replay. */
    val byId: Map<String, Card> =
        (chance + communityChest).associateBy { it.id }

    init {
        require(byId.size == chance.size + communityChest.size) { "Duplicate card id" }
    }

    fun requireCard(id: String): Card =
        byId[id] ?: error("Unknown card id: $id")
}
