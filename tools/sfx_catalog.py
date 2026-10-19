"""
Builds the app's medieval sound library from CC0 (public domain) packs on OpenGameArt.

Usage: python3 sfx_catalog.py <raw_dir> <out_assets_sfx_dir>

Every sound is converted to mono Ogg Vorbis, trimmed to at most 30 s (with a fade),
and loudness-matched so taps across the board play at a similar volume.
"""
import json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

RAW, OUT = sys.argv[1], sys.argv[2]

# (category key, title, icon, short description)
CATEGORIES = [
    ("doors", "Doors & Locks", "🚪", "Doors, gates, keys, locks and chests"),
    ("creaks", "Creaks & Wood", "🪵", "Creaking boards, wood knocks and breaks"),
    ("coins", "Coins & Treasure", "💰", "Coin purses, clinks, gems and loot"),
    ("tavern", "Tavern & Food", "🍺", "Drinks, dishes, dice and dining"),
    ("home", "Home & Workshop", "🏠", "Books, parchment, cloth, tools and bells"),
    ("weapons", "Weapons & Armor", "⚔️", "Swords, arrows, armor, impacts and cannons"),
    ("people", "People", "🧑", "Footsteps, coughs, laughs and crowds"),
    ("animals", "Animals", "🐾", "Birds, cats, dogs, farm animals and horses"),
    ("monsters", "Monsters", "👹", "Goblins, ogres, trolls, ghosts and beasts"),
    ("magic", "Magic", "✨", "Spells, fire magic, potions and bubbles"),
    ("nature", "Nature & Weather", "🌲", "Rain, thunder, wind, fire, water and stone"),
]

# (category, pack folder, filename regex, label, max picks)
RULES = [
    # Doors & Locks
    ("doors", "50-rpg-sound-effects", r"doorOpen_", "Door opens", 2),
    ("doors", "50-rpg-sound-effects", r"doorClose_", "Door shuts", 3),
    ("doors", "100-cc0-sfx", r"^door_0[12]\.", "Creaky old door", 2),
    ("doors", "100-cc0-sfx", r"^door_open", "Door opens quickly", 1),
    ("doors", "100-cc0-sfx", r"^door_close_", "Door closes", 3),
    ("doors", "100-cc0-sfx", r"^slam_0[1-3]", "Door slams", 3),
    ("doors", "rpg-sound-pack", r"world_door", "Heavy door", 1),
    ("doors", "100-cc0-metal-and-wood-sfx", r"^metal_open_", "Iron gate opens", 1),
    ("doors", "100-cc0-metal-and-wood-sfx", r"^metal_close_", "Iron gate closes", 1),
    ("doors", "95-sfx-pack-beats-warrior-nian", r"rockdoor_open\.", "Stone door grinds", 1),
    ("doors", "open-chest-sfx", r".", "Treasure chest opens", 1),
    ("doors", "100-cc0-sfx", r"wooded_box_open", "Wooden box opens", 1),
    ("doors", "fantasy-sound-effects-tinysized-sfx", r"drawer-open", "Drawer opens", 1),
    ("doors", "fantasy-sound-effects-tinysized-sfx", r"drawer-close", "Drawer closes", 1),
    ("doors", "fantasy-accessory-sfx-library", r"keyhole-lockbox-insert", "Key slides into lock", 2),
    ("doors", "fantasy-accessory-sfx-library", r"keyhole-lockbox-unlock", "Lock clicks open", 3),
    ("doors", "100-cc0-sfx", r"^key_open_", "Key turns", 2),
    ("doors", "fantasy-accessory-sfx-library", r"keys-rattle", "Key ring rattles", 3),
    ("doors", "100-cc0-metal-and-wood-sfx", r"^keys_0[1-3]", "Keys jingle", 3),
    ("doors", "202-more-sound-effects", r"Key_Lock_Door_0[1-6]\.", "Lock and latch", 6),
    ("doors", "fantasy-sound-effects-tinysized-sfx", r"handcuffs-metal-lock", "Shackles lock", 2),
    ("doors", "fantasy-sound-effects-tinysized-sfx", r"handcuffs-metal-open", "Shackles open", 1),
    ("doors", "50-rpg-sound-effects", r"metalLatch", "Metal latch", 1),
    # Creaks & Wood
    ("creaks", "50-rpg-sound-effects", r"creak[123]", "Creak", 3),
    ("creaks", "fantasy-sound-effects-tinysized-sfx", r"floor-creak", "Floorboard creaks", 1),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_squeak", "Wood squeaks", 2),
    ("creaks", "chain-winch-sounds", r".", "Winch creaks", 4),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_hit_0[1-4]", "Knock on wood", 4),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_cracking_0[1-3]", "Wood cracks", 3),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_breaking", "Wood breaks", 2),
    ("creaks", "75-cc0-breaking-falling-hit-sfx", r"wood_breaking_0[1-3]", "Plank snaps", 3),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_falling_0[1-3]", "Wood clatters", 3),
    ("creaks", "100-cc0-metal-and-wood-sfx", r"wood_slam", "Wooden slam", 2),
    ("creaks", "fantasy-sound-effects-tinysized-sfx", r"wood-twigs-break", "Twig snaps", 2),
    ("creaks", "100-cc0-sfx", r"^wooden_0", "Wood tap", 3),
    # Coins & Treasure
    ("coins", "50-rpg-sound-effects", r"handleCoins", "Coin purse", 2),
    ("coins", "fantasy-accessory-sfx-library", r"coins-shake-0[1-4]", "Coin pouch shakes", 4),
    ("coins", "fantasy-sound-effects-tinysized-sfx", r"cloth-pouch-shake", "Pouch jingles", 2),
    ("coins", "80-cc0-rpg-sfx", r"item_coins", "Coins clink", 4),
    ("coins", "rpg-sound-pack", r"inventory_coin", "Coins drop", 3),
    ("coins", "202-more-sound-effects", r"Money_Money_(0[1-9]|1[0-2])\.", "Counting coins", 8),
    ("coins", "fantasy-accessory-sfx-library", r"coinflip", "Coin flip", 3),
    ("coins", "fantasy-accessory-sfx-library", r"coin-spin-fall-0[1-3]", "Coin spins and falls", 3),
    ("coins", "80-cc0-rpg-sfx", r"item_gem", "Gem sparkles", 4),
    ("coins", "rpg-sound-pack", r"inventory_beads", "Beads", 1),
    ("coins", "metal-impact-sounds", r"clink", "Small clink", 3),
    # Tavern & Food
    ("tavern", "202-more-sound-effects", r"Drink_Drink", "Gulp a drink", 6),
    ("tavern", "fantasy-sound-effects-tinysized-sfx", r"water-pour", "Pour a drink", 1),
    ("tavern", "100-cc0-sfx", r"^glass_0", "Glass clinks", 5),
    ("tavern", "fantasy-accessory-sfx-library", r"bottle-glass-uncork-0[1-3]", "Uncork a bottle", 3),
    ("tavern", "fantasy-accessory-sfx-library", r"bottle-clay-uncork-0[12]", "Uncork a clay jug", 2),
    ("tavern", "cutlery-sounds", r"cup_on_table", "Mug set down", 1),
    ("tavern", "cutlery-sounds", r"fork_on_dish", "Fork on plate", 3),
    ("tavern", "cutlery-sounds", r"drop_cutlery", "Cutlery drops", 1),
    ("tavern", "100-cc0-sfx", r"^dishes_", "Dishes rattle", 4),
    ("tavern", "50-rpg-sound-effects", r"metalPot", "Iron pot", 3),
    ("tavern", "fantasy-sound-effects-tinysized-sfx", r"wood-bowl-spoon-0[12]", "Bowl and spoon", 2),
    ("tavern", "fantasy-sound-effects-tinysized-sfx", r"apple-cut-0[12]", "Slice an apple", 2),
    ("tavern", "7-eating-crunches", r"crunch\.[1-4]\.", "Crunch and chew", 4),
    ("tavern", "rpg-sound-pack", r"misc_burp", "Burp", 1),
    ("tavern", "80-cc0-creature-sfx", r"^burp_", "Belch", 2),
    ("tavern", "fantasy-accessory-sfx-library", r"dice-two-roll-0[1-3]", "Roll dice", 3),
    ("tavern", "fantasy-accessory-sfx-library", r"dice-many-roll-0[12]", "Roll a handful of dice", 2),
    ("tavern", "fantasy-accessory-sfx-library", r"dice-many-shake-0[12]", "Shake dice cup", 2),
    ("tavern", "30-cc0-sfx-loops", r"water_boiling", "Stew pot boils", 1),
    ("tavern", "202-more-sound-effects", r"Kitchen_Kitchen_0[1-6]\.", "Kitchen clatter", 6),
    # Home & Workshop
    ("home", "10-book-page-flips", r"book_flip\.[1-4]\.", "Turn a page", 4),
    ("home", "50-rpg-sound-effects", r"bookOpen", "Open a book", 1),
    ("home", "50-rpg-sound-effects", r"bookClose", "Close a book", 1),
    ("home", "80-cc0-rpg-sfx", r"^book_0", "Heavy tome", 4),
    ("home", "100-cc0-sfx", r"^paper_0", "Unroll a scroll", 4),
    ("home", "202-more-sound-effects", r"Paper_Stationery_Stationery_0[1-5]\.", "Parchment", 5),
    ("home", "fantasy-sound-effects-tinysized-sfx", r"cover-paper-tear", "Tear parchment", 2),
    ("home", "50-rpg-sound-effects", r"OGG_cloth[1-4]", "Cloth rustles", 4),
    ("home", "50-rpg-sound-effects", r"clothBelt", "Buckle a belt", 2),
    ("home", "50-rpg-sound-effects", r"dropLeather", "Drop a leather bag", 1),
    ("home", "202-more-sound-effects", r"Cloth_Cloth_0[1-4]\.", "Cloak swish", 4),
    ("home", "hammer-on-anvil", r".", "Blacksmith at the anvil", 1),
    ("home", "blacksmiths-hammer", r"blacksmithhammer\.", "Hammer strike", 1),
    ("home", "fantasy-sound-effects-tinysized-sfx", r"metal-hammer-hit", "Hammer on metal", 2),
    ("home", "100-cc0-metal-and-wood-sfx", r"^hammer_0", "Hammer tap", 4),
    ("home", "fantasy-sound-effects-tinysized-sfx", r"handsaw-sawing-wood", "Saw wood", 1),
    ("home", "saw-or-wood-impact", r".", "Sawing", 1),
    ("home", "100-cc0-metal-and-wood-sfx", r"^tools_0[1-4]", "Tools clink", 4),
    ("home", "fantasy-sound-effects-tinysized-sfx", r"metal-knife-scrape-0[12]", "Sharpen a blade", 2),
    ("home", "100-cc0-sfx", r"^bell_", "Bell", 3),
    ("home", "100-cc0-sfx", r"^gong_", "Gong", 2),
    ("home", "metal-impact-sounds", r"^bong1", "Deep chime", 1),
    ("home", "fantasy-sound-effects-tinysized-sfx", r"match-light", "Strike a flame", 1),
    # Weapons & Armor
    ("weapons", "rpg-sound-pack", r"sword-unsheathe", "Draw a sword", 5),
    ("weapons", "fantasy-sound-effects-tinysized-sfx", r"sword-clash", "Swords clash", 5),
    ("weapons", "rpg-sound-pack", r"battle_swing", "Swing", 3),
    ("weapons", "swishes-sound-pack", r"swish-(1|2|3|7|9)\.", "Whoosh", 5),
    ("weapons", "3-melee-sounds", r"sword_sound", "Sword hit", 1),
    ("weapons", "3-melee-sounds", r"melee_sounds_melee_sound", "Blunt hit", 1),
    ("weapons", "80-cc0-rpg-sfx", r"^blade_", "Blade ring", 3),
    ("weapons", "50-rpg-sound-effects", r"drawKnife", "Draw a dagger", 3),
    ("weapons", "fantasy-sound-effects-tinysized-sfx", r"seax-sheathe-0[12]", "Sheathe a dagger", 2),
    ("weapons", "50-rpg-sound-effects", r"knifeSlice", "Slash", 2),
    ("weapons", "50-rpg-sound-effects", r"OGG_chop", "Chop", 1),
    ("weapons", "fantasy-sound-effects-tinysized-sfx", r"arrow-grab-from-quiver", "Draw an arrow", 2),
    ("weapons", "fantasy-sound-effects-tinysized-sfx", r"arrow-return-to-quiver", "Arrow back in quiver", 2),
    ("weapons", "fantasy-sound-effects-tinysized-sfx", r"arrow-feathers", "Arrow fletching", 1),
    ("weapons", "rpg-sound-pack", r"inventory_chainmail", "Chainmail", 2),
    ("weapons", "rpg-sound-pack", r"inventory_armor-light", "Leather armor", 1),
    ("weapons", "80-cc0-rpg-sfx", r"^chain_", "Chains rattle", 3),
    ("weapons", "rpg-sound-pack", r"metal-ringing", "Shield rings", 1),
    ("weapons", "75-cc0-breaking-falling-hit-sfx", r"metal_hit_0[1-3]", "Metal impact", 3),
    ("weapons", "75-cc0-breaking-falling-hit-sfx", r"bfh1_hit_0[1-4]", "Heavy impact", 4),
    ("weapons", "metal-clang-sounds", r"Marker_[12]\.", "Great clang", 2),
    ("weapons", "battle-at-sea", r"cannon_fire_1", "Cannon fires", 1),
    ("weapons", "battle-at-sea", r"cannon_hit_wall_1", "Cannonball hits a wall", 1),
    ("weapons", "battle-at-sea", r"cannon_miss_1", "Cannonball splashes", 1),
    # People
    ("people", "50-rpg-sound-effects", r"footstep0[0-4]", "Footstep", 5),
    ("people", "100-cc0-sfx-2", r"footstep_wood", "Steps on wood", 3),
    ("people", "100-cc0-sfx-2", r"footstep_wet", "Wet steps", 2),
    ("people", "fantasy-sound-effects-tinysized-sfx", r"boots-leather-step", "Leather boot step", 1),
    ("people", "fantasy-sound-effects-tinysized-sfx", r"boots-leather-jump", "Jump and land", 1),
    ("people", "fantasy-sound-effects-tinysized-sfx", r"mud-steps-0[12]", "Steps in mud", 2),
    ("people", "fantasy-sound-effects-tinysized-sfx", r"soil-steps", "Steps on dirt", 1),
    ("people", "80-cc0-creature-sfx", r"^cough_", "Cough", 3),
    ("people", "old-man-cough", r"old-man-cough\.", "Old man coughs", 1),
    ("people", "80-cc0-creature-sfx", r"^snore", "Snore", 1),
    ("people", "80-cc0-creture-sfx-2", r"^snore_", "Loud snore", 1),
    ("people", "80-cc0-creature-sfx", r"^breath", "Heavy breath", 1),
    ("people", "80-cc0-creature-sfx", r"^ooh", "Ooh!", 1),
    ("people", "80-cc0-creature-sfx", r"^spit_", "Spit", 2),
    ("people", "sick-noises", r".", "Feeling sick", 1),
    ("people", "do-you-remember-laughter", r".", "Crowd laughs", 1),
    ("people", "applause-in-a-large-hall-or-church", r".", "Applause in a great hall", 1),
    ("people", "80-cc0-creture-sfx-2", r"^human_0[1-4]", "Pained grunt", 4),
    # Animals
    ("animals", "crow-caw", r".", "Crow caws", 1),
    ("animals", "crow", r".", "Raven croaks", 1),
    ("animals", "crows-singing", r".", "Flock of crows", 1),
    ("animals", "vulture-imitation", r"vulture-1\.", "Vulture", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"_bird\.", "Songbird", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"_bird2", "Songbird trills", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"birdNight", "Night bird", 1),
    ("animals", "bird-chirping-sounds", r"birdchirping071414\.", "Birds chirp", 1),
    ("animals", "95-sfx-pack-beats-warrior-nian", r"home_bird_wing", "Wings flap", 1),
    ("animals", "95-sfx-pack-beats-warrior-nian", r"snd_bat\.", "Bat flutters", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"cricket2", "Crickets", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"_frog", "Frog croaks", 1),
    ("animals", "birdcricketfrog-and-mosquito-sounds", r"Mosquito", "Mosquito", 1),
    ("animals", "cat-purr-meow", r"cat_(mewfood|softmew|mewpurr\.)", "Cat meows", 3),
    ("animals", "cat-purr-meow", r"purrsleepy", "Cat purrs", 1),
    ("animals", "meow", r".", "Meow", 1),
    ("animals", "80-cc0-creature-sfx", r"^barking_", "Dog barks", 2),
    ("animals", "dog-grunt", r".", "Dog grumbles", 1),
    ("animals", "80-cc0-creature-sfx", r"^howl", "Wolf howls", 1),
    ("animals", "sheep-baa", r"sheep_baa\.", "Sheep bleats", 1),
    ("animals", "yak-imitation", r"^yak\.", "Yak grunts", 1),
    ("animals", "sci-fi-aliens-and-cows-pack", r"moo", "Cow moos", 2),
    ("animals", "horse-trotting", r".", "Horse trots", 1),
    ("animals", "rabbit-eating", r".", "Rabbit nibbles", 1),
    ("animals", "80-cc0-creature-sfx", r"^bug_0[12]", "Insect buzz", 2),
    # Monsters
    ("monsters", "goblins-sound-pack", r"goblin-([1-8])\.", "Goblin", 8),
    ("monsters", "rpg-sound-pack", r"NPC_ogre", "Ogre", 5),
    ("monsters", "rpg-sound-pack", r"NPC_giant", "Giant", 5),
    ("monsters", "80-cc0-creature-sfx", r"^troll_", "Troll", 3),
    ("monsters", "big-scary-troll-sounds", r"troll-roars", "Troll roars", 1),
    ("monsters", "big-scary-troll-sounds", r"troll-idle", "Troll grumbles", 1),
    ("monsters", "rpg-sound-pack", r"NPC_shade", "Wraith", 6),
    ("monsters", "ghost-monster-voice-moaning-growling", r"_mp3_", "Ghost moans", 4),
    ("monsters", "rpg-sound-pack", r"gutteral_beast", "Beast growls", 5),
    ("monsters", "rpg-sound-pack", r"misc_wolfman", "Werewolf", 1),
    ("monsters", "wolf-monster-sound", r".", "Dire wolf", 1),
    ("monsters", "80-cc0-rpg-sfx", r"creature_roar", "Monster roars", 3),
    ("monsters", "80-cc0-creture-sfx-2", r"^roar_0[4-6]", "Great roar", 3),
    ("monsters", "80-cc0-creature-sfx", r"^monster_0[1-4]", "Creature", 4),
    ("monsters", "monster-sound-pack-volume-1", r"Monster-[1-6]\.", "Monster", 6),
    ("monsters", "80-cc0-creature-sfx", r"^scream_", "Creature shrieks", 2),
    ("monsters", "80-cc0-creture-sfx-2", r"^die_0[1-3]", "Creature dies", 3),
    ("monsters", "80-cc0-creture-sfx-2", r"^stomp", "Heavy stomp", 1),
    ("monsters", "rpg-sound-pack", r"NPC_beetle", "Giant beetle", 2),
    ("monsters", "rpg-sound-pack", r"NPC_slime_slime[1-3]\.", "Ooze", 3),
    ("monsters", "evil-laugh", r".", "Evil laugh", 1),
    # Magic
    ("magic", "rpg-sound-pack", r"battle_magic1", "Magic flare", 1),
    ("magic", "rpg-sound-pack", r"battle_spell", "Spell cast", 1),
    ("magic", "80-cc0-rpg-sfx", r"^spell_0", "Spell", 2),
    ("magic", "80-cc0-rpg-sfx", r"spell_fire_0[1-5]", "Fireball", 5),
    ("magic", "fire-staff-sound-effects", r".", "Fire staff", 2),
    ("magic", "100-cc0-sfx", r"^explosion", "Arcane blast", 1),
    ("magic", "95-sfx-pack-beats-warrior-nian", r"icebrust_snowflake\.", "Ice burst", 1),
    ("magic", "95-sfx-pack-beats-warrior-nian", r"ghost_gone", "Vanish", 1),
    ("magic", "metal-impact-sounds", r"^bing1", "Magic chime", 1),
    ("magic", "rpg-sound-pack", r"inventory_bubble", "Potion bubbles", 3),
    ("magic", "40-cc0-water-splash-slime-sfx", r"^bubble_0", "Cauldron bubbles", 3),
    ("magic", "fantasy-accessory-sfx-library", r"vial-glass-round-uncork-0[12]", "Open a potion", 2),
    ("magic", "fantasy-accessory-sfx-library", r"vials-glass-rattle-0[12]", "Potions rattle", 2),
    ("magic", "40-cc0-water-splash-slime-sfx", r"^slime_0[1-3]", "Slimy spell", 3),
    # Nature & Weather
    ("nature", "30-cc0-sfx-loops", r"^rain", "Rain", 1),
    ("nature", "40-cc0-water-splash-slime-sfx", r"loop_rain", "Steady rain", 1),
    ("nature", "100-cc0-sfx-2", r"thunder", "Thunder", 1),
    ("nature", "wind1", r"wind[12]\.", "Wind", 2),
    ("nature", "random-sfx", r"Hollow_Wind", "Hollow wind", 1),
    ("nature", "fire-crackling", r"fire-1\.", "Campfire crackles", 1),
    ("nature", "fireplace-sound-loop", r".", "Fireplace", 1),
    ("nature", "30-cc0-sfx-loops", r"water_flowing", "Stream", 1),
    ("nature", "40-cc0-water-splash-slime-sfx", r"loop_water_0[12]", "Running water", 2),
    ("nature", "random-sfx", r"Background_Falls", "Waterfall", 1),
    ("nature", "water-waves", r"wave_0[12]", "Waves", 2),
    ("nature", "dripping-water-loop", r".", "Dripping cave", 1),
    ("nature", "fantasy-sound-effects-tinysized-sfx", r"water-drop-0[12]", "Water drop", 2),
    ("nature", "40-cc0-water-splash-slime-sfx", r"^splash_0[1-4]", "Splash", 4),
    ("nature", "ambient-bird-sounds", r".", "Forest birdsong", 1),
    ("nature", "100-cc0-sfx-2", r"stones_", "Stones shift", 3),
    ("nature", "75-cc0-breaking-falling-hit-sfx", r"rock_breaking_0[1-3]", "Rock breaks", 3),
    ("nature", "75-cc0-breaking-falling-hit-sfx", r"rock_falling_0[1-3]", "Rockfall", 3),
]

MAX_SECONDS = 30.0


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                       capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return 0.0


def levels(path):
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-t", str(MAX_SECONDS), "-ac", "1",
                        "-af", "volumedetect", "-f", "null", "-"], capture_output=True, text=True)
    mean = re.search(r"mean_volume: (-?[\d.]+) dB", r.stderr)
    peak = re.search(r"max_volume: (-?[\d.]+) dB", r.stderr)
    return (float(mean.group(1)) if mean else -30.0, float(peak.group(1)) if peak else -3.0)


def convert(job):
    src, dst = job["src"], job["dst"]
    dur = probe(src)
    mean, peak = levels(src)
    # Bring every sound to a similar loudness; a limiter tames peaks so quiet,
    # spiky sounds (clinks, knocks) can be lifted without clipping.
    gain = max(-12.0, min(-19.0 - mean, 15.0))
    filters = [f"volume={gain:.2f}dB", "alimiter=limit=0.6:attack=2:release=40:level=disabled"]
    length = min(dur, MAX_SECONDS) if dur > 0 else MAX_SECONDS
    if dur > MAX_SECONDS:
        filters.append(f"afade=t=out:st={MAX_SECONDS - 1.5:.2f}:d=1.5")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src, "-t", f"{length:.3f}",
           "-ac", "1", "-ar", "44100", "-af", ",".join(filters), "-c:a", "libvorbis", "-q:a", "4", dst]
    subprocess.run(cmd, check=True)
    job["dur"] = round(min(dur, MAX_SECONDS), 2)
    return job


def main():
    os.makedirs(OUT, exist_ok=True)
    jobs, used = [], set()
    counts = {}
    for cat, pack, pattern, label, limit in RULES:
        folder = os.path.join(RAW, pack)
        if not os.path.isdir(folder):
            print(f"missing pack {pack}", file=sys.stderr); continue
        files = sorted(f for f in os.listdir(folder) if re.search(pattern, f) and (pack, f) not in used)
        files = [f for f in files if not f.startswith("preview")][:limit]
        if not files:
            print(f"no match {pack} {pattern}", file=sys.stderr); continue
        for i, f in enumerate(files):
            used.add((pack, f))
            counts[cat] = counts.get(cat, 0) + 1
            name = label if len(files) == 1 else f"{label} {i + 1}"
            sid = f"{cat}_{counts[cat]:03d}"
            os.makedirs(os.path.join(OUT, cat), exist_ok=True)
            jobs.append({"cat": cat, "id": sid, "label": name, "src": os.path.join(folder, f),
                         "dst": os.path.join(OUT, cat, sid + ".ogg"), "source": pack})
    with ThreadPoolExecutor(8) as ex:
        jobs = list(ex.map(convert, jobs))
    catalog = []
    for key, title, icon, desc in CATEGORIES:
        sounds = [{"id": j["id"], "label": j["label"], "file": f"sfx/{key}/{j['id']}.ogg", "dur": j["dur"]}
                  for j in jobs if j["cat"] == key]
        catalog.append({"key": key, "title": title, "icon": icon, "desc": desc, "sounds": sounds})
        print(f"{icon} {title}: {len(sounds)}")
    with open(os.path.join(OUT, "catalog.json"), "w", encoding="utf-8") as fh:
        json.dump({"categories": catalog}, fh, ensure_ascii=False, indent=1)
    credits = sorted({j["source"] for j in jobs})
    with open(os.path.join(OUT, "CREDITS.txt"), "w") as fh:
        fh.write("All sounds are CC0 (public domain) from OpenGameArt.org packs:\n")
        fh.writelines(f"https://opengameart.org/content/{c}\n" for c in credits)
    print("total", len(jobs))


if __name__ == "__main__":
    main()
