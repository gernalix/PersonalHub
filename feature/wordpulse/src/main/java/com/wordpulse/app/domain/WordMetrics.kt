package com.wordpulse.app.domain

data class WordMetrics(
    val general: GeneralMetrics = GeneralMetrics(),
    val timing: TimingMetrics = TimingMetrics(),
    val wordStats: WordStats = WordStats(),
    val repeatedWords: RepeatedWordMetrics = RepeatedWordMetrics(),
    val fragments: FragmentMetrics = FragmentMetrics(),
    val similarity: SimilarityMetrics = SimilarityMetrics(),
    val scores: List<ScoreMetric> = emptyList(),
    val baselines: List<BaselineComparison> = emptyList(),
    val dashboard: DashboardMetrics = DashboardMetrics(),
    val sessionStoryline: SessionStorylineMetrics = SessionStorylineMetrics(),
    val corrections: CorrectionMetrics = CorrectionMetrics(),
    val disclosure: ProgressiveDisclosure = ProgressiveDisclosure(),
    val entropy: EntropyMetrics = EntropyMetrics(),
    val vocabulary: VocabularyMetrics = VocabularyMetrics(),
    val rhythm: RhythmMetrics = RhythmMetrics(),
    val families: FamilyMetrics = FamilyMetrics(),
    val evolution: EvolutionMetrics = EvolutionMetrics(),
    val phases: GenerativePhaseMetrics = GenerativePhaseMetrics(),
    val creativeEpisodes: CreativeEpisodeMetrics = CreativeEpisodeMetrics(),
    val genealogy: WordGenealogyMetrics = WordGenealogyMetrics(),
    val languageEpochs: LanguageEpochMetrics = LanguageEpochMetrics(),
    val phonotactics: PhonotacticMetrics = PhonotacticMetrics(),
    val consonantSpine: ConsonantSpineMetrics = ConsonantSpineMetrics(),
    val vowelPalette: VowelPaletteMetrics = VowelPaletteMetrics(),
    val soundShapeDrift: SoundShapeDriftMetrics = SoundShapeDriftMetrics(),
    val sessionContrast: SessionContrastMetrics = SessionContrastMetrics(),
    val motifRoutes: MotifRouteMetrics = MotifRouteMetrics(),
    val motifAttractors: MotifAttractorMetrics = MotifAttractorMetrics(),
    val memoryEchoes: MemoryEchoMetrics = MemoryEchoMetrics(),
    val constellations: WordConstellationMetrics = WordConstellationMetrics(),
    val familyMigrations: FamilyMigrationMetrics = FamilyMigrationMetrics(),
    val timeline: List<TimelineEntry> = emptyList(),
    val insights: List<Insight> = emptyList(),
)

data class GeneralMetrics(
    val totalSubmittedWords: Int = 0,
    val uniqueWords: Int = 0,
    val duplicateCount: Int = 0,
    val duplicatePercentage: Double = 0.0,
    val todaysWords: Int = 0,
    val lastSevenDaysWords: Int = 0,
    val currentSessionWords: Int = 0,
)

data class TimingMetrics(
    val wordsPerMinute: Double = 0.0,
    val charactersPerMinute: Double = 0.0,
    val averageSubmissionIntervalMs: Double? = null,
    val longestPauseMs: Long? = null,
    val shortestPauseMs: Long? = null,
    val fastestOneMinuteBurst: Int = 0,
)

data class WordStats(
    val averageLength: Double = 0.0,
    val medianLength: Double = 0.0,
    val shortestWords: List<String> = emptyList(),
    val longestWords: List<String> = emptyList(),
    val lengthDistribution: List<LengthBucket> = emptyList(),
)

data class LengthBucket(
    val length: Int,
    val count: Int,
)

data class RepeatedWordMetrics(
    val mostCommonWords: List<WordOccurrenceSummary> = emptyList(),
    val newestUniqueWords: List<WordOccurrenceSummary> = emptyList(),
    val repeatedWords: List<WordOccurrenceSummary> = emptyList(),
)

data class WordOccurrenceSummary(
    val normalizedWord: String,
    val sampleOriginalWord: String,
    val count: Int,
    val firstOccurrenceUtcMs: Long,
    val latestOccurrenceUtcMs: Long,
)

data class FragmentMetrics(
    val prefixesByLength: Map<Int, List<FragmentCount>> = emptyMap(),
    val suffixesByLength: Map<Int, List<FragmentCount>> = emptyMap(),
    val letterNgramsByLength: Map<Int, List<FragmentCount>> = emptyMap(),
    val repeatedInternalFragments: List<FragmentCount> = emptyList(),
    val usefulFragments: List<RankedFragment> = emptyList(),
    val newFragments: List<RankedFragment> = emptyList(),
    val growingFragments: List<RankedFragment> = emptyList(),
    val decliningFragments: List<RankedFragment> = emptyList(),
    val stableFragments: List<RankedFragment> = emptyList(),
    val lifecycles: List<FragmentLifecycle> = emptyList(),
    val ecology: MotifEcologyMetrics = MotifEcologyMetrics(),
)

data class FragmentCount(
    val fragment: String,
    val count: Int,
)

data class RankedFragment(
    val fragment: String,
    val count: Int,
    val documentFrequency: Int,
    val usefulness: Double,
    val trend: FragmentTrend,
)

data class FragmentLifecycle(
    val fragment: String,
    val windowCounts: List<Int>,
    val totalDocuments: Int,
    val peakWindow: Int,
    val trend: FragmentTrend,
    val movementScore: Double,
)

data class MotifEcologyMetrics(
    val stories: List<MotifEcologyStory> = emptyList(),
    val dominantStory: MotifEcologyStory? = null,
    val narrative: String? = null,
)

data class MotifEcologyStory(
    val fragment: String,
    val windowCounts: List<Int>,
    val birthWindow: Int,
    val peakWindow: Int,
    val extinctionWindow: Int?,
    val status: String,
    val interpretation: String,
    val examples: List<String>,
    val intensity: Double,
)

enum class FragmentTrend {
    New,
    Growing,
    Declining,
    Stable,
}

data class SimilarityMetrics(
    val averageSimilarity: Double? = null,
    val medianSimilarity: Double? = null,
    val highestSimilarity: SimilarityPair? = null,
    val lowestSimilarity: SimilarityPair? = null,
    val distribution: List<SimilarityBucket> = emptyList(),
    val averageEditDistance: Double? = null,
    val medianEditDistance: Double? = null,
)

data class SimilarityPair(
    val previousWord: String,
    val currentWord: String,
    val distance: Int,
    val similarity: Double,
)

data class SimilarityBucket(
    val label: String,
    val count: Int,
)

data class ScoreMetric(
    val name: String,
    val value: Double,
    val formula: String,
)

data class BaselineComparison(
    val name: String,
    val todayValue: Double,
    val sevenDayAverage: Double?,
    val thirtyDayAverage: Double?,
    val allTimeAverage: Double?,
    val sevenDayDeviationPercentage: Double?,
    val thirtyDayDeviationPercentage: Double?,
    val allTimeDeviationPercentage: Double?,
    val isUnusual: Boolean,
)

data class DashboardMetrics(
    val currentSessionWords: Int = 0,
    val wordsToday: Int = 0,
    val currentStreak: Int = 0,
    val wordsPerMinute: Double = 0.0,
    val flowScore: Double = 0.0,
    val noveltyScore: Double = 0.0,
    val repetitionScore: Double = 0.0,
    val averageWordLength: Double = 0.0,
    val timeSinceLastWordMs: Long? = null,
    val currentSessionDurationMs: Long? = null,
    val todayVsSevenDayDeviationPercentage: Double? = null,
)

data class SessionStorylineMetrics(
    val headline: String = "Keep writing to reveal the session storyline.",
    val narrative: String = "The storyline appears once the current session has enough words to show a stable generative shape.",
    val segments: List<SessionStorylineSegment> = emptyList(),
    val turningPoints: List<SessionStorylineMarker> = emptyList(),
    val evidence: List<SessionStorylineSignal> = emptyList(),
)

data class SessionStorylineSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val phaseLabel: String,
    val dominantMotif: String?,
    val episodeLabel: String?,
    val epochLabel: String?,
    val noveltyPercentage: Double,
    val repetitionPercentage: Double,
    val averageEditDistance: Double?,
    val paceWordsPerMinute: Double,
    val intensity: Double,
    val reading: String,
)

data class SessionStorylineMarker(
    val label: String,
    val ordinal: Int,
    val detail: String,
    val tone: InsightTone = InsightTone.Neutral,
)

data class SessionStorylineSignal(
    val label: String,
    val value: String,
    val detail: String,
    val tone: InsightTone = InsightTone.Neutral,
)

data class CorrectionMetrics(
    val totalCorrections: Int = 0,
    val currentSessionCorrections: Int = 0,
    val correctionRatePercentage: Double = 0.0,
    val currentSessionCorrectionRatePercentage: Double = 0.0,
    val lastCorrectionLatencyMs: Long? = null,
    val medianCorrectionLatencyMs: Double? = null,
)

data class ProgressiveDisclosure(
    val showSimilarity: Boolean = false,
    val showFragments: Boolean = false,
    val showBaselines: Boolean = false,
    val showFamilies: Boolean = false,
    val showEvolution: Boolean = false,
    val showGenerativePhases: Boolean = false,
    val showCreativeEpisodes: Boolean = false,
    val showGenealogy: Boolean = false,
    val showLanguageEpochs: Boolean = false,
    val showPhonotactics: Boolean = false,
    val showConsonantSpine: Boolean = false,
    val showVowelPalette: Boolean = false,
    val showSoundShapeDrift: Boolean = false,
    val showSessionContrast: Boolean = false,
    val showMotifRoutes: Boolean = false,
    val showMotifAttractors: Boolean = false,
    val showMemoryEchoes: Boolean = false,
    val showConstellations: Boolean = false,
    val showFamilyMigrations: Boolean = false,
)

data class EntropyMetrics(
    val lexicalEntropyBits: Double? = null,
    val characterEntropyBits: Double? = null,
    val fragmentEntropyBits: Double? = null,
    val compressionRatio: Double? = null,
)

data class VocabularyMetrics(
    val vocabularyGrowthRate: Double = 0.0,
    val noveltyDecayPercentage: Double? = null,
    val longestNoveltyStreak: Int = 0,
    val longestDuplicateStreak: Int = 0,
    val wordFamilyDiversity: Int = 0,
)

data class RhythmMetrics(
    val sessionDensity: Double = 0.0,
    val burstCount: Int = 0,
    val rhythmStability: Double? = null,
    val wordEvolutionSpeed: Double? = null,
    val averageRecurrenceLatencyMs: Double? = null,
    val longestRecurrenceLatencyMs: Long? = null,
    val shortestRecurrenceLatencyMs: Long? = null,
    val circadianPeakHour: Int? = null,
    val weeklyPeakDay: String? = null,
)

data class FamilyMetrics(
    val families: List<WordFamily> = emptyList(),
    val recurringFamilyScore: Double = 0.0,
)

data class EvolutionMetrics(
    val transitions: List<EvolutionTransition> = emptyList(),
    val dominantTransitionLabel: String? = null,
    val continuityPercentage: Double? = null,
    val averageDriftDistance: Double? = null,
    val mutationGrammar: MutationGrammarMetrics = MutationGrammarMetrics(),
)

data class EvolutionTransition(
    val label: String,
    val count: Int,
    val sharePercentage: Double,
    val averageEditDistance: Double,
    val examples: List<String>,
)

data class MutationGrammarMetrics(
    val operators: List<MutationOperatorSummary> = emptyList(),
    val segments: List<MutationGrammarSegment> = emptyList(),
    val choreography: List<MutationOperatorTransition> = emptyList(),
    val dominantOperator: String? = null,
    val dominantChoreography: String? = null,
    val narrative: String? = null,
)

data class MutationOperatorSummary(
    val label: String,
    val count: Int,
    val sharePercentage: Double,
    val averageEditDistance: Double,
    val interpretation: String,
    val examples: List<String>,
)

data class MutationGrammarSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantOperator: String,
    val sharePercentage: Double,
    val example: String,
)

data class MutationOperatorTransition(
    val fromOperator: String,
    val toOperator: String,
    val count: Int,
    val sharePercentage: Double,
    val relationship: String,
    val examples: List<String>,
)

data class GenerativePhaseMetrics(
    val segments: List<GenerativePhaseSegment> = emptyList(),
    val dominantLabel: String? = null,
    val phaseSwitchCount: Int = 0,
    val fingerprint: List<SessionFingerprintDimension> = emptyList(),
)

data class GenerativePhaseSegment(
    val label: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantFragment: String?,
    val noveltyPercentage: Double,
    val repetitionPercentage: Double,
    val averageEditDistance: Double?,
    val paceWordsPerMinute: Double,
    val intensity: Double,
)

data class CreativeEpisodeMetrics(
    val episodes: List<CreativeEpisode> = emptyList(),
    val highlightedEpisode: CreativeEpisode? = null,
    val creativePressure: Double? = null,
    val repetitivePressure: Double? = null,
)

data class CreativeEpisode(
    val label: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val noveltyPercentage: Double,
    val repetitionPercentage: Double,
    val averageEditDistance: Double?,
    val dominantMotif: String?,
    val intensity: Double,
    val interpretation: String,
)

data class SessionFingerprintDimension(
    val label: String,
    val value: Double,
    val detail: String,
)

data class WordGenealogyMetrics(
    val lineages: List<WordLineage> = emptyList(),
    val links: List<WordGenealogyLink> = emptyList(),
    val rootCount: Int = 0,
    val branchingWord: String? = null,
    val ancestryContinuityPercentage: Double? = null,
)

data class WordLineage(
    val rootWord: String,
    val words: List<String>,
    val depth: Int,
    val branchCount: Int,
    val averageParentDistance: Double,
)

data class WordGenealogyLink(
    val parentWord: String,
    val childWord: String,
    val distance: Int,
    val ordinal: Int,
    val relationship: String,
)

data class LanguageEpochMetrics(
    val epochs: List<LanguageEpoch> = emptyList(),
    val shifts: List<LanguageShift> = emptyList(),
    val strongestShift: LanguageShift? = null,
    val averageShiftIntensity: Double? = null,
)

data class LanguageEpoch(
    val label: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantMotif: String?,
    val dominantMotifSharePercentage: Double,
    val noveltyPercentage: Double,
    val repetitionPercentage: Double,
    val averageEditDistance: Double?,
    val averageWordLength: Double,
    val intensity: Double,
)

data class LanguageShift(
    val fromLabel: String,
    val toLabel: String,
    val boundaryOrdinal: Int,
    val intensity: Double,
    val motifChange: String,
    val noveltyDeltaPercentage: Double,
    val driftDelta: Double,
    val label: String,
)

data class PhonotacticMetrics(
    val lanes: List<PhonotacticLane> = emptyList(),
    val transitions: List<PhonotacticTransition> = emptyList(),
    val skeletons: List<PhonotacticSkeleton> = emptyList(),
    val rules: List<PhonotacticRule> = emptyList(),
    val dominantRule: String? = null,
)

data class PhonotacticLane(
    val role: String,
    val motifs: List<PhonotacticMotif>,
)

data class PhonotacticMotif(
    val motif: String,
    val coveragePercentage: Double,
    val strength: Double,
    val examples: List<String>,
)

data class PhonotacticTransition(
    val from: String,
    val to: String,
    val coveragePercentage: Double,
    val examples: List<String>,
)

data class PhonotacticSkeleton(
    val pattern: String,
    val coveragePercentage: Double,
    val examples: List<String>,
)

data class PhonotacticRule(
    val scope: String,
    val pattern: String,
    val confidencePercentage: Double,
    val exceptionCount: Int,
    val interpretation: String,
    val examples: List<String>,
    val exceptions: List<String>,
)

data class SoundShapeDriftMetrics(
    val segments: List<SoundShapeSegment> = emptyList(),
    val shifts: List<SoundShapeShift> = emptyList(),
    val dominantShape: String? = null,
    val shapeStabilityPercentage: Double? = null,
    val narrative: String? = null,
)

data class SoundShapeSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantSkeleton: String,
    val sharePercentage: Double,
    val averageLength: Double,
    val examples: List<String>,
)

data class SoundShapeShift(
    val fromSkeleton: String,
    val toSkeleton: String,
    val boundaryOrdinal: Int,
    val intensity: Double,
    val relationship: String,
)

data class ConsonantSpineMetrics(
    val segments: List<ConsonantSpineSegment> = emptyList(),
    val shifts: List<ConsonantSpineShift> = emptyList(),
    val dominantSpine: String? = null,
    val strongestShift: ConsonantSpineShift? = null,
    val narrative: String? = null,
)

data class ConsonantSpineSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantSpine: String,
    val sharePercentage: Double,
    val averageVowelSlots: Double,
    val examples: List<String>,
)

data class ConsonantSpineShift(
    val fromSpine: String,
    val toSpine: String,
    val boundaryOrdinal: Int,
    val intensity: Double,
    val relationship: String,
)

data class VowelPaletteMetrics(
    val segments: List<VowelPaletteSegment> = emptyList(),
    val shifts: List<VowelPaletteShift> = emptyList(),
    val dominantPalette: String? = null,
    val strongestShift: VowelPaletteShift? = null,
    val narrative: String? = null,
)

data class VowelPaletteSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantVowel: String,
    val paletteLabel: String,
    val dominantSharePercentage: Double,
    val shares: List<VowelShare>,
    val examples: List<String>,
)

data class VowelShare(
    val vowel: String,
    val percentage: Double,
)

data class VowelPaletteShift(
    val fromPalette: String,
    val toPalette: String,
    val boundaryOrdinal: Int,
    val intensity: Double,
    val relationship: String,
)

data class SessionContrastMetrics(
    val dimensions: List<SessionContrastDimension> = emptyList(),
    val nearestSessionLabel: String? = null,
    val similarityPercentage: Double? = null,
    val strongestDifference: SessionContrastDimension? = null,
    val historicalSessionsCompared: Int = 0,
)

data class SessionContrastDimension(
    val label: String,
    val currentValue: Double,
    val historicalValue: Double,
    val deltaPoints: Double,
    val interpretation: String,
)

data class MotifRouteMetrics(
    val routes: List<MotifRoute> = emptyList(),
    val segments: List<MotifRouteSegment> = emptyList(),
    val dominantRouteLabel: String? = null,
    val handoffCount: Int = 0,
    val loopPercentage: Double? = null,
)

data class MotifRoute(
    val fromMotif: String,
    val toMotif: String,
    val count: Int,
    val sharePercentage: Double,
    val relationship: String,
    val examples: List<String>,
)

data class MotifRouteSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantMotif: String,
    val sharePercentage: Double,
)

data class MotifAttractorMetrics(
    val attractors: List<MotifAttractor> = emptyList(),
    val timeline: List<MotifAttractorSegment> = emptyList(),
    val strongestAttractor: MotifAttractor? = null,
    val narrative: String? = null,
)

data class MotifAttractor(
    val motif: String,
    val occupancyPercentage: Double,
    val returnCount: Int,
    val longestRun: Int,
    val averageEntryDrift: Double?,
    val pullStrength: Double,
    val interpretation: String,
    val examples: List<String>,
)

data class MotifAttractorSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantMotif: String,
    val sharePercentage: Double,
)

data class MemoryEchoMetrics(
    val links: List<MemoryEchoLink> = emptyList(),
    val segments: List<MemoryEchoSegment> = emptyList(),
    val strongestEcho: MemoryEchoLink? = null,
    val echoDensityPercentage: Double? = null,
    val narrative: String? = null,
)

data class MemoryEchoLink(
    val previousWord: String,
    val currentWord: String,
    val previousOrdinal: Int,
    val currentOrdinal: Int,
    val gap: Int,
    val distance: Int,
    val relationship: String,
    val strength: Double,
)

data class MemoryEchoSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val echoCount: Int,
    val averageStrength: Double,
)

data class WordConstellationMetrics(
    val constellations: List<WordConstellation> = emptyList(),
    val edges: List<WordConstellationEdge> = emptyList(),
    val densestConstellationLabel: String? = null,
    val connectedWordPercentage: Double? = null,
)

data class WordConstellation(
    val label: String,
    val words: List<String>,
    val edgeCount: Int,
    val densityPercentage: Double,
    val averageDistance: Double,
)

data class WordConstellationEdge(
    val fromWord: String,
    val toWord: String,
    val distance: Int,
    val relationship: String,
)

data class FamilyMigrationMetrics(
    val families: List<LatentWordFamily> = emptyList(),
    val transitions: List<FamilyMigrationTransition> = emptyList(),
    val segments: List<FamilyMigrationSegment> = emptyList(),
    val dominantPath: String? = null,
    val narrative: String? = null,
)

data class LatentWordFamily(
    val label: String,
    val words: List<String>,
    val occupancyPercentage: Double,
    val averageDistance: Double,
)

data class FamilyMigrationTransition(
    val fromFamily: String,
    val toFamily: String,
    val count: Int,
    val sharePercentage: Double,
    val boundaryOrdinal: Int,
    val relationship: String,
    val examples: List<String>,
)

data class FamilyMigrationSegment(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val dominantFamily: String,
    val sharePercentage: Double,
)

data class WordFamily(
    val label: String,
    val words: List<String>,
    val totalOccurrences: Int,
    val latestOccurrenceUtcMs: Long,
)

data class TimelineEntry(
    val id: Long,
    val originalWord: String,
    val normalizedWord: String,
    val createdAtUtcMs: Long,
    val elapsedSincePreviousMs: Long?,
    val wordLength: Int,
    val duplicateOrdinal: Int,
    val similarityToPrevious: Double?,
    val editDistanceToPrevious: Int?,
)

data class Insight(
    val title: String,
    val value: String,
    val detail: String,
    val tone: InsightTone,
)

enum class InsightTone {
    Neutral,
    Positive,
    Attention,
}

data class WordDetail(
    val normalizedWord: String,
    val sampleOriginalWord: String,
    val totalOccurrences: Int,
    val firstOccurrenceUtcMs: Long,
    val latestOccurrenceUtcMs: Long,
    val sessionIds: List<String>,
    val averageIntervalBetweenOccurrencesMs: Double?,
    val longestIntervalBetweenOccurrencesMs: Long?,
    val shortestIntervalBetweenOccurrencesMs: Long?,
    val closestSimilarWords: List<WordNeighbor>,
    val prefix: String,
    val suffix: String,
    val detectedFragments: List<String>,
)

data class WordNeighbor(
    val normalizedWord: String,
    val sampleOriginalWord: String,
    val distance: Int,
    val similarity: Double,
    val occurrences: Int,
)

enum class SearchMode {
    Substring,
    Prefix,
    Suffix,
    Fuzzy,
    Similar,
    Regex,
}

enum class SearchSort {
    Frequency,
    Recency,
    Alphabetical,
    Length,
}

data class WordSearchResult(
    val normalizedWord: String,
    val sampleOriginalWord: String,
    val occurrences: Int,
    val latestOccurrenceUtcMs: Long,
    val length: Int,
    val distance: Int? = null,
)
