package dev.infusion.core.database

import androidx.room.TypeConverter
import dev.infusion.core.database.model.AccessState
import dev.infusion.core.database.model.AvailabilityKind
import dev.infusion.core.database.model.CapabilityState
import dev.infusion.core.database.model.EditionKind
import dev.infusion.core.database.model.EventOrigin
import dev.infusion.core.database.model.ExpectedMode
import dev.infusion.core.database.model.HealthState
import dev.infusion.core.database.model.ReachabilityState
import dev.infusion.core.database.model.SecretKind
import dev.infusion.core.database.model.SourceType
import dev.infusion.core.database.model.TitleKind
import dev.infusion.core.database.model.TitleRelation
import dev.infusion.core.database.model.TrustClass
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus
import dev.infusion.core.model.SourceHealth
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchStatus

class Converters {
    @TypeConverter fun titleKind(v: TitleKind): String = v.name
    @TypeConverter fun titleKind(s: String): TitleKind = TitleKind.valueOf(s)

    @TypeConverter fun titleRelation(v: TitleRelation): String = v.name
    @TypeConverter fun titleRelation(s: String): TitleRelation = TitleRelation.valueOf(s)

    @TypeConverter fun editionKind(v: EditionKind): String = v.name
    @TypeConverter fun editionKind(s: String): EditionKind = EditionKind.valueOf(s)

    @TypeConverter fun sourceType(v: SourceType): String = v.name
    @TypeConverter fun sourceType(s: String): SourceType = SourceType.valueOf(s)

    @TypeConverter fun trustClass(v: TrustClass): String = v.name
    @TypeConverter fun trustClass(s: String): TrustClass = TrustClass.valueOf(s)

    @TypeConverter fun healthState(v: HealthState): String = v.name
    @TypeConverter fun healthState(s: String): HealthState = HealthState.valueOf(s)

    @TypeConverter fun sourceHealth(v: SourceHealth): String = v.name
    @TypeConverter fun sourceHealth(s: String): SourceHealth = SourceHealth.valueOf(s)

    @TypeConverter fun secretKind(v: SecretKind): String = v.name
    @TypeConverter fun secretKind(s: String): SecretKind = SecretKind.valueOf(s)

    @TypeConverter fun accessState(v: AccessState): String = v.name
    @TypeConverter fun accessState(s: String): AccessState = AccessState.valueOf(s)

    @TypeConverter fun availabilityKind(v: AvailabilityKind): String = v.name
    @TypeConverter fun availabilityKind(s: String): AvailabilityKind = AvailabilityKind.valueOf(s)

    @TypeConverter fun expectedMode(v: ExpectedMode): String = v.name
    @TypeConverter fun expectedMode(s: String): ExpectedMode = ExpectedMode.valueOf(s)

    @TypeConverter fun capabilityState(v: CapabilityState): String = v.name
    @TypeConverter fun capabilityState(s: String): CapabilityState = CapabilityState.valueOf(s)

    @TypeConverter fun reachabilityState(v: ReachabilityState): String = v.name
    @TypeConverter fun reachabilityState(s: String): ReachabilityState = ReachabilityState.valueOf(s)

    @TypeConverter fun eventOrigin(v: EventOrigin): String = v.name
    @TypeConverter fun eventOrigin(s: String): EventOrigin = EventOrigin.valueOf(s)

    @TypeConverter fun watchEventKind(v: WatchEvent.Kind): String = v.name
    @TypeConverter fun watchEventKind(s: String): WatchEvent.Kind = WatchEvent.Kind.valueOf(s)

    @TypeConverter fun watchStatus(v: WatchStatus): String = v.name
    @TypeConverter fun watchStatus(s: String): WatchStatus = WatchStatus.valueOf(s)

    @TypeConverter fun outboxState(v: OutboxState): String = v.name
    @TypeConverter fun outboxState(s: String): OutboxState = OutboxState.valueOf(s)

    @TypeConverter fun mappingStatus(v: MappingStatus): String = v.name
    @TypeConverter fun mappingStatus(s: String): MappingStatus = MappingStatus.valueOf(s)

    @TypeConverter fun mappingOrigin(v: MappingOrigin): String = v.name
    @TypeConverter fun mappingOrigin(s: String): MappingOrigin = MappingOrigin.valueOf(s)
}
