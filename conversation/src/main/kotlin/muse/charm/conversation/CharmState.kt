package muse.charm.conversation

/** The four things the charm can be doing. Nothing else gets a screen. */
enum class CharmState(val label: String) {
    IDLE("대기"),
    LISTENING("듣는 중"),
    THINKING("생각 중"),
    SPEAKING("말하는 중"),
    ;

    fun next(): CharmState = entries[(ordinal + 1) % entries.size]
}
