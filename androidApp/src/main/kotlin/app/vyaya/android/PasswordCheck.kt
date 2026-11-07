package app.vyaya.android

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Passwords for protected backups (SEC-002 of reports/android-lock-encryption-2026-11-06.md). A stolen .vyaya file
 * can be attacked offline, so the guesses tried first (common passwords, keyboard rows, sequences, one repeated
 * character, the app's own name, any of them with digits added) are refused, and a 5-word passphrase is offered.
 */
object PasswordCheck {
    const val MIN_LENGTH = 8

    /** Why [password] isn't good enough, or null. */
    fun problem(password: String): String? {
        if (password.length < MIN_LENGTH) return "At least $MIN_LENGTH characters"
        val p = password.lowercase()
        val core = p.trimEnd { it.isDigit() || it in "!@#$%^&*._-" }.trimStart { it.isDigit() }
        return when {
            p.toSet().size <= 2 -> "Too easy to guess: use more different characters"
            p in COMMON || core in COMMON || COMMON.any { it.length >= 5 && p.startsWith(it) && p.removePrefix(it).none { c -> c.isLetter() } } ->
                "That is one of the most common passwords: try a passphrase of a few words"
            isRun(p) -> "Too easy to guess (a keyboard row or a sequence)"
            p.all { it.isDigit() } && p.length < 12 -> "Only digits: use at least 12, or words"
            "vyaya" in p && p.replace("vyaya", "").count { it.isLetter() } < 4 -> "Don't base it on the app's name"
            else -> null
        }
    }

    /** Five random words from [WORDS] joined by "-": about 45 bits, easy to write down. */
    fun passphrase(random: Random = SecureRandom().asKotlinRandom()): String = List(5) { WORDS[random.nextInt(WORDS.size)] }.joinToString("-")

    private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "abcdefghijklmnopqrstuvwxyz")

    /** Most of it is one run along a keyboard row or the alphabet, forwards or backwards. */
    private fun isRun(p: String): Boolean = ROWS.any { row ->
        listOf(row, row.reversed()).any { r -> (0..r.length - 6).any { i -> p.contains(r.substring(i, i + 6)) } && p.count { it in r } >= p.length - 2 }
    }

    private val COMMON = setOf(
        "password", "passw0rd", "p@ssword", "password1", "password123", "123456", "1234567", "12345678", "123456789", "1234567890",
        "12345678910", "111111", "000000", "654321", "987654321", "qwerty", "qwertyuiop", "asdfghjkl", "zxcvbnm", "1q2w3e4r",
        "1qaz2wsx", "qwerty123", "abc123", "abcd1234", "abcdefgh", "iloveyou", "iloveu", "welcome", "welcome1", "admin",
        "admin123", "letmein", "monkey", "dragon", "sunshine", "princess", "football", "cricket", "baseball", "master",
        "shadow", "superman", "batman", "trustno1", "whatever", "freedom", "starwars", "computer", "internet", "india123",
        "india@123", "bharat", "krishna", "ganesh", "sairam", "omsairam", "jaishreeram", "hanuman", "mother", "family",
        "secret", "changeme", "default", "loveyou", "lovely", "flower", "mobile", "samsung", "iphone", "google",
        "facebook", "instagram", "whatsapp", "paytm", "phonepe", "money", "rupees", "bank", "savings", "expense",
        "budget", "vyaya",
    )

    /** Simple, everyday English words (written for Vyaya; no outside list). */
    val WORDS: List<String> = listOf(
        "able", "acid", "acorn", "actor", "adapt", "agent", "alarm", "album", "alert", "alley", "almond", "alpha", "amber", "angle",
        "ankle", "apple", "april", "apron", "arena", "arrow", "aside", "atlas", "attic", "audio", "aunt", "autumn", "avocado", "award",
        "awake", "axis", "baby", "bacon", "badge", "bagel", "baker", "balmy", "bamboo", "banana", "band", "banjo", "barley", "barn",
        "basil", "basin", "basket", "batch", "beach", "beacon", "beam", "bean", "bear", "beard", "beaver", "bed", "bee", "beetle",
        "bell", "belt", "bench", "berry", "bicycle", "bird", "biscuit", "blade", "blanket", "blaze", "blend", "blink", "bloom", "blue",
        "blush", "board", "boat", "bolt", "bonus", "book", "boot", "border", "bottle", "boulder", "bowl", "box", "brave", "bread",
        "breeze", "brick", "bridge", "brief", "bright", "brook", "broom", "brush", "bubble", "bucket", "buddy", "budget", "buffalo", "bugle",
        "bunny", "butter", "button", "cabin", "cable", "cactus", "cake", "calm", "camel", "camera", "camp", "canal", "candle", "candy",
        "canoe", "canvas", "canyon", "cape", "captain", "card", "cargo", "carpet", "carrot", "cart", "castle", "cat", "cave", "cedar",
        "cello", "cereal", "chain", "chair", "chalk", "chapter", "charm", "cheek", "cheese", "cherry", "chess", "chest", "chick", "chili",
        "chimney", "chip", "chorus", "cider", "cinema", "circle", "citrus", "city", "clam", "clay", "cliff", "climb", "clock", "cloud",
        "clover", "coach", "coast", "cobalt", "cocoa", "coconut", "coffee", "coin", "comet", "comic", "compass", "cookie", "copper", "coral",
        "corn", "cotton", "couch", "country", "cousin", "cradle", "crane", "crayon", "cream", "creek", "cricket", "crisp", "crown", "crystal",
        "cube", "cucumber", "cup", "curry", "curtain", "cushion", "cycle", "daisy", "dance", "dawn", "deer", "delta", "denim", "desert",
        "desk", "dew", "diamond", "diary", "dinner", "dish", "dock", "dolphin", "domino", "donkey", "door", "dove", "dragon", "drama",
        "drawer", "dream", "drum", "duck", "dune", "dust", "eagle", "earth", "easel", "echo", "eclipse", "eel", "egg", "elbow",
        "elephant", "elm", "ember", "emerald", "engine", "envelope", "equal", "eraser", "essay", "evening", "fabric", "falcon", "fan", "farm",
        "feather", "fence", "fern", "ferry", "festival", "fiddle", "field", "fig", "film", "finch", "fire", "fish", "flag", "flame",
        "flannel", "flash", "flute", "foam", "fog", "folder", "forest", "fork", "fossil", "fountain", "fox", "frame", "frog", "frost",
        "fruit", "fudge", "galaxy", "garden", "garlic", "gate", "gecko", "gem", "giant", "ginger", "giraffe", "glacier", "glass", "globe",
        "glove", "goat", "gold", "goose", "gourd", "grain", "grape", "grass", "gravel", "gravy", "green", "grid", "guitar", "gull",
        "habit", "hammer", "hammock", "harbor", "harp", "hat", "hawk", "hazel", "heart", "hedge", "helmet", "herb", "heron", "hill",
        "hippo", "honey", "hood", "hook", "horizon", "horse", "hotel", "house", "hummus", "husky", "igloo", "index", "ink", "island",
        "ivory", "ivy", "jacket", "jade", "jam", "jar", "jasmine", "jeans", "jelly", "jet", "jewel", "jigsaw", "jungle", "juniper",
        "kayak", "kettle", "key", "kite", "kitten", "kiwi", "knee", "knot", "koala", "ladder", "lake", "lamp", "lantern", "laptop",
        "lark", "lava", "lawn", "leaf", "lemon", "lentil", "letter", "lily", "lime", "linen", "lion", "lizard", "llama", "lobster",
        "locket", "lotus", "lunar", "lunch", "lychee", "magnet", "mango", "maple", "marble", "market", "meadow", "melon", "menu", "meteor",
        "mint", "mirror", "mist", "mitten", "modem", "molar", "monkey", "moon", "moose", "mosaic", "moss", "motor", "mountain", "mouse",
        "muffin", "mural", "mushroom", "music", "mustard", "napkin", "nectar", "needle", "nest", "net", "nickel", "noodle", "north", "notebook",
        "nutmeg", "oak", "oasis", "oat", "ocean", "octave", "olive", "omelet", "onion", "opal", "orange", "orbit", "orchid", "otter",
        "oven", "owl", "oyster", "paddle", "page", "palm", "panda", "paper", "parade", "parrot", "pasta", "peach", "peacock", "peanut",
        "pear", "pearl", "pebble", "pedal", "pelican", "pencil", "pepper", "piano", "pickle", "picnic", "pigeon", "pillow", "pilot", "pine",
        "pizza", "planet", "plum", "pocket", "poem", "polar", "pond", "popcorn", "poppy", "potato", "pottery", "prism", "pudding", "pumpkin",
        "puppy", "puzzle", "quail", "quartz", "quiet", "quilt", "rabbit", "radar", "radio", "radish", "raft", "rain", "rainbow", "raisin",
        "ranch", "raven", "recipe", "reef", "rhino", "ribbon", "rice", "ridge", "river", "robin", "rocket", "rose", "ruby", "rug",
        "saddle", "safari", "saffron", "sail", "salad", "salmon", "salt", "sand", "sandal", "satin", "saucer", "scarf", "school", "scooter",
        "scroll", "sea", "seal", "seed", "shadow", "shell", "shelf", "ship", "shore", "silk", "silver", "sketch", "sky", "sled",
        "slipper", "smile", "snail", "snow", "soap", "sock", "sofa", "solar", "sonnet", "soup", "spice", "spider", "spinach", "sponge",
        "spoon", "spring", "sprout", "squash", "squid", "stamp", "star", "station", "steam", "stone", "storm", "straw", "stream", "street",
        "sugar", "summer", "sun", "sunset", "swan", "sweater", "swing", "table", "taco", "tail", "tango", "tea", "teapot", "temple",
        "tent", "thunder", "ticket", "tiger", "timber", "toast", "tomato", "topaz", "torch", "tortoise", "towel", "tower", "trail", "train",
        "tree", "trout", "tulip", "tuna", "tunnel", "turkey", "turnip", "turtle", "tusk", "twig", "umbrella", "unicorn", "valley", "vanilla",
        "vase", "velvet", "violet", "violin", "voyage", "wafer", "wagon", "walnut", "walrus", "wand", "water", "wave", "wheat", "wheel",
        "whale", "whistle", "willow", "window", "winter", "wizard", "wolf", "wombat", "wood", "wool", "yacht", "yak", "yarn", "yoga",
        "yogurt", "zebra", "zero", "zinc", "zipper", "zone",
    )
}
