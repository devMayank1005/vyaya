package app.vyaya.shared

import app.vyaya.core.model.Sha256
import kotlin.random.Random

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
            isCommon(p) || isCommon(core) || (5 until p.length).any { n -> p.drop(n).none { c -> c.isLetter() } && isCommon(p.take(n)) } ->
                "That is one of the most common passwords: try a passphrase of a few words"
            isRun(p) -> "Too easy to guess (a keyboard row or a sequence)"
            p.all { it.isDigit() } && p.length < 12 -> "Only digits: use at least 12, or words"
            "vyaya" in p && p.replace("vyaya", "").count { it.isLetter() } < 4 -> "Don't base it on the app's name"
            else -> null
        }
    }

    /** Five random words from [WORDS] joined by "-": about 45 bits, easy to write down. */
    fun passphrase(random: Random = secureRandom()): String = List(5) { WORDS[random.nextInt(WORDS.size)] }.joinToString("-")

    private fun isCommon(s: String): Boolean = sha256(s) in COMMON_SHA256

    private fun sha256(s: String): String = Sha256().apply { update(s) }.hexDigest()

    private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "abcdefghijklmnopqrstuvwxyz")

    /** Most of it is one run along a keyboard row or the alphabet, forwards or backwards. */
    private fun isRun(p: String): Boolean = ROWS.any { row ->
        listOf(row, row.reversed()).any { r -> (0..r.length - 6).any { i -> p.contains(r.substring(i, i + 6)) } && p.count { it in r } >= p.length - 2 }
    }

    /**
     * SHA-256 of the lowercase common passwords refused (password, qwerty, 12345678, iloveyou, india123, paytm and the
     * like; 82 in all). Kept as hashes, not text, so the source holds no password list for scanners to flag.
     */
    private val COMMON_SHA256 = setOf(
        "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", "8f0e2f76e22b43e2855189877e7dc1e1e7d98c226c95db247cd1d547928334a9", "0fd205965ce169b5c023282bb5fa2e239b6716726db5defaa8ceff225be805dc",
        "0b14d501a594442a01c6859541bcb3e8164d183d32937b851835442f69d5c94e", "ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f", "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92",
        "8bb0cf6eb9b17d0f7d22b456f121257dc1254e1f01665370476383ea776df414", "ef797c8118f02dfb649607dd5d3f8c7623048c9c063d532cc95c5ed7a898a64f", "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
        "c775e7b757ede630cd0aa1113bd102661ab38829ca52a6422ab782862f268646", "63640264849a87c90356129d99ea165e37aa5fabc1fea46906df1a7ca50db492", "bcb15f821479b4d5772bd0ca866c00ad5f926e3580720659cc80d39c9d09802a",
        "91b4d142823f7d20c5f08df69122de43f35f057a988d9619f6d3138485c9a203", "481f6cc0511143ccdd7e2d1b1b94faf0a700a8b49cd13922a70b5ae28acaa8c5", "8a9bcf1e51e812d0af8465a8dbcc9f741064bf0af3b3d08e6b0246437c19f7fb",
        "65e84be33532fb784c48129675f9eff3a682b27168c0ea744b2cf58ee02337c5", "9a900403ac313ba27a1bc81f0932652b8020dac92c234d98fa0b06bf0040ecfd", "5c80565db6f29da0b01aa12522c37b32f121cbe47a861ef7f006cb22922dffa1",
        "1df1854015e31ca286d015345eaff29a6c6073f70984a3a746823d4cac16b075", "72ab994fa2eb426c051ef59cad617750bfe06d7cf6311285ff79c19c32afd236", "059a00192592d5444bc0caad7203f98b506332e2cf7abb35d684ea9bf7c18f08",
        "daaad6e5604e8e17bd9f108d91e26afe6281dac8fda0091040a7a6d7bd9b43b5", "6ca13d52ca70c883e0f0bb101e425a89e8624de51db2d2392593af6a84118090", "e9cee71ab932fde863338d08be4de9dfe39ea049bdafb342ce659ec5450b69ae",
        "9c56cc51b374c3ba189210d5b6d4bf57790d351c96c47c02190ecf1e430635ab", "e4ad93ca07acb8d908a3aa41e920ea4f4ef4f26e7f86cf8291c5db289780a5ae", "3972905cc58310b37c7989feb13592ea71cf0902fd0fe4415782ece0c469d22c",
        "280d44ab1e9f79b5cce2dd4f58f5fe91f0fbacdac9f7447dffc318ceb79f2d02", "fcc3a23fc7232cc89c7cb0f23d8774fefb73d7dc2ab22e6a1b6b8b202b4dcc91", "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918",
        "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9", "1c8bfe8f801d79745c4631d09fff36c82aa37fc4cce4fc946683d7b336b63032", "000c285457fc971f862a79b786476c78812c8897063c6fa9c045f579a3b2d63f",
        "a9c43be948c5cabd56ef2bacffb77cdaa5eec49dd5eb0cc4129cf3eda5f0e74c", "a941a4c4fd0c01cddef61b8be963bf4c1e2b0811c037ce3f1835fddf6ef6c223", "04e77bf8f95cb3e1a36a59d1e93857c411930db646b46c218a0352e432023cf2",
        "6382deaf1f5dc6e792b76db4a4a7bf2ba468884e000b25e7928e621e27fb23cb", "1134e4f4a13b06aeaa8471f934589a3e180bf3fe432e32e1f9454e76a90077e7", "a01edad91c00abe7be5b72b5e36bf4ce3c6f26e8bce3340eba365642813ab8b6",
        "fc613b4dfd6736a7bd268c8a0e74ed0d1c04a959f59dd74ef2874983fd443fc9", "0bb09d80600eec3eb9d7793a6f859bedde2a2d83899b70bd78e961ed674b32f4", "73cd1b16c4fb83061ad18a0b29b9643a68d4640075a466dc9e51682f84a847f5",
        "1532e76dbe9d43d0dea98c331ca5ae8a65c5e8e8b99d3e2a42ae989356f6242a", "203b70b5ae883932161bbd0bded9357e763e63afce98b16230be33f0b94c2cc5", "85738f8f9a7f1b04b5329c590ebcb9e425925c6d0984089c43a022de4f19c281",
        "13b1f7ec5beaefc781e43a3b344371cd49923a8a05edd71844b92f56f6a08d38", "74fca0325b5fdb3a34badb40a2581cfbd5344187e8d3432952a5abc0929c1246", "aa97302150fce811425cd84537028a5afbe37e3f1362ad45a51d467e17afdc9c",
        "3b0fe0d342e9fa16a5c68dbba33f2e63c024f72a9d4c1ce1028570101d5229ff", "74d91c557471ae2be44157b3ae7e93f2f26fe67427b6ce91894ebf4f325f90e8", "877ce6fe4191d42aaf9bfdfe45dba062136afdd6b08bf90d86aa62677c918ab5",
        "b736100361ded8976e400f768df86e0b8a384ddaed59f07da9b4dbe9f56fa91b", "b06514d8bc65875cc670a226aa945c492c126ef2da71f226c3ed938aacb27f30", "2415f50d4aa44c1aeefdee019793fce717601a184ab1f140c0af1a03c2e4b8ac",
        "e9c5162436ad1a16360979104c442393933da0fec5af3b3962bbea4dba3288bb", "56b545585c0b519bbd263793a96253f87bd85c0110dbe1d7903a692ff0c406f1", "98fd16a06a765b91c2f9f6d831fccd4082e91f5544876c2ff0cca75099aaddf8",
        "1ca35897540ec7ae7294a8cacd11caf2c09f95f026c925c6ce4e4c29b20e3c41", "cf0622ef2a661cd2f11b0b644e40e4e00eb962918424a8c8ba53842fdf290235", "d34a569ab7aaa54dacd715ae64953455d86b768846cd0085ef4e9e7471489b7b",
        "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b", "057ba03d6c44104863dc7361fe4578965d1887360f90a0895882e58a6248fc86", "37a8eec1ce19687d132fe29051dca629d164e2c4958ba141d5f4133a33f0688f",
        "848be944013cc3374ddfef3655d6816c060610161c835c71a9665e6dc18f6542", "f85b43067a280d4cc40f89cb78d8efff6e908727bc3cb43a9ae72b400d2eecc5", "c06b0cfe0cc5e900c57784484094331f095bf441995c3c31ea6c75691c786c35",
        "d524c1a0811da49592f841085cc0063eb62b3001252a94542795d1ca9824a941", "968e2d5b08687bf42997461cbdef6c844eabbf04f440cee888c95b864c2a4bcc", "241c1e30ed886aa4a8f4248024be2ca1a221fe9773b52e2dca7891ff5771f399",
        "bbdefa2950f49882f295b1285d4fa9dec45fc4144bfb07ee6acc68762d12c2e3", "3d59f7548e1af2151b64135003ce63c0a484c26b9b8b166a7b1c1805ec34b00a", "5ede56738376a8c53a7c99bf275b077a5c10df780a20cf3d8282fd59a2a9fb56",
        "ec8202b6f9fb16f9e26b66367afa4e037752f3c09a18cefab426165e06a424b1", "2b1da9c840074a3558ef73664428ba21374be164833182f12d90e2372ed89c9e", "0f8ec60dd129da14ddebd1a1a76ec9bf6f6fb9e61f04fb9d52ef21191a5bc086",
        "8d2ac8b58ead9744d77286de9b0bcb7a894f238c3149fc9f3b1e3caff36330fe", "007d7b7286b6e507f5f3db5e0baf100dc13fd6ed1c279cac21bd5d7c8c36fbd1", "4381dc2ab14285160c808659aee005d51255add7264b318d07c7417292c7442c",
        "286d4726e10586b5919a577d6bd248091f92b32979a267efbf06e6ce69ec54c4", "23b18e504dd45960690a04669a3f89b3b9672d878218b99aa84011784c9174f5", "0af96a8ed622a394e8b2a239284ee46e9a7a7b0ec38191bbd08571b171118dd6",
        "16324089b5a9b029a5341f7fc4800131a8569832aad705cfeb47dfba69d7b11b",
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
