package org.familycircle.android.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.Alert
import org.familycircle.android.api.MapMember
import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.map.CameraFit
import org.familycircle.android.map.EdgeInsets
import org.familycircle.android.map.MapStyle
import org.familycircle.android.map.MarkerModel
import org.familycircle.android.map.MarkerSource
import org.familycircle.android.map.MarkerSpec
import org.familycircle.android.map.MarkerVisual
import org.familycircle.android.map.PlaceShape
import org.familycircle.android.map.SosPin
import org.familycircle.android.tracking.TrackingRuntime
import org.familycircle.android.ui.alerts.OthersSosBanner
import org.familycircle.android.ui.alerts.OwnSosBanner
import org.familycircle.android.ui.alerts.SafetyViewModel
import org.familycircle.android.ui.alerts.SosHoldButton
import org.familycircle.android.ui.alerts.safetyMessageText
import org.familycircle.android.ui.components.FreshnessBadge
import org.familycircle.android.ui.components.uiErrorMessage
import org.familycircle.android.ui.family.PlaceDialog
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.MemberAvatar
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.motionText
import org.familycircle.android.ui.components.relativeAgeText
import org.familycircle.android.ui.components.rememberNow
import org.familycircle.android.ui.components.shortAgeText
import org.familycircle.android.ui.components.uiErrorText
import org.familycircle.android.ui.components.windowWidthDp
import org.familycircle.android.ui.family.Freshness
import org.familycircle.android.ui.home.TrackingStatus
import org.familycircle.android.ui.theme.LocalStatusColors
import kotlin.math.roundToInt

/** Width from which the member list becomes a side panel next to the map. */
private const val WIDE_DP = 600

/**
 * The family map (start screen after onboarding): markers with profile pictures, a member list
 * (bottom sheet on phones, side panel on wide screens), live updates while visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onOpenSharing: () -> Unit, snackbar: SnackbarHostState) {
    val container = LocalAppContainer.current
    val vm = containerViewModel { MapViewModel(it) }
    val safety = containerViewModel { SafetyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sos by safety.sos.collectAsStateWithLifecycle()
    val safetyState by safety.state.collectAsStateWithLifecycle()
    val safetyMessage by safety.message.collectAsStateWithLifecycle()
    val focusRequest by container.mapFocus.request.collectAsStateWithLifecycle()
    val liveState by vm.liveState.collectAsStateWithLifecycle()
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val queue by container.dao.countFlow().collectAsStateWithLifecycle(0)
    val running by TrackingRuntime.running.collectAsStateWithLifecycle()
    val now by rememberNow(15_000)
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Live socket + polling only while the map is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.runWhileVisible() }
    }

    val specs = remember(state.live, state.localSelf, now) { MarkerModel.build(state.live.members, state.localSelf, now) }
    val membersById = remember(state.live) { state.live.members.associateBy { it.userId } }
    val mapMarkers = specs.map { spec -> MapMarker(spec, markerVisual(spec, membersById[spec.userId], state, now)) }

    var cameraNonce by remember { mutableIntStateOf(0) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    var styleFailed by remember { mutableStateOf(false) }
    val styleUrl = remember(prefs.mapStyleUrl, prefs.serverUrl) { MapStyle.resolve(prefs.mapStyleUrl, prefs.serverUrl) }

    val status = TrackingStatus.derive(running, prefs.trackingDesired, prefs.trackingPausedByUser)
    val widthDp = windowWidthDp()
    val wide = widthDp >= WIDE_DP

    var mapSize by remember { mutableStateOf(0 to 0) }
    var topOverlayPx by remember { mutableIntStateOf(0) }

    fun focus(userId: String?) {
        cameraNonce++
        cameraRequest = CameraRequest(cameraNonce, userId)
    }

    fun focusPoint(lat: Double, lon: Double) {
        cameraNonce++
        cameraRequest = CameraRequest(cameraNonce, null, lat, lon)
    }

    fun showSos(alert: Alert) {
        val loc = alert.location
        if (loc != null) focusPoint(loc.lat, loc.lon) else focus(alert.actor.userId)
    }

    val messageText = safetyMessage?.let { safetyMessageText(it) }
    LaunchedEffect(safetyMessage) {
        if (messageText != null) {
            safety.messageShown()
            snackbar.showSnackbar(messageText)
        }
    }

    // "Show on map" from a notification (SOS: the SOS position).
    LaunchedEffect(focusRequest, state.loaded) {
        val request = focusRequest ?: return@LaunchedEffect
        if (!state.loaded || !container.mapFocus.take(request)) return@LaunchedEffect
        val sosAlert = sos.others.firstOrNull { it.actor.userId == request.userId }
        if (request.sos && sosAlert != null) {
            showSos(sosAlert)
        } else {
            vm.select(request.userId)
            focus(request.userId)
        }
    }

    val placeShapes = remember(state.places) { state.places.map(PlaceShape::from) }
    val sosPins = remember(sos.others) { sos.others.mapNotNull(SosPin::from) }
    var newPlaceAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var placeBusy by remember { mutableStateOf(false) }
    val placeAdded = stringResource(R.string.place_added)

    val panelContent: @Composable (Modifier) -> Unit = { modifier ->
        MemberPanel(
            state = state,
            specs = specs,
            now = now,
            liveState = liveState,
            onSelectFamily = vm::selectFamily,
            onShowAll = { focus(null) },
            onSelectMember = { id ->
                vm.select(id)
                focus(id)
            },
            modifier = modifier,
        )
    }

    val mapLayer: @Composable (paddingProvider: () -> EdgeInsets, onMemberTapped: (String) -> Unit) -> Unit = { paddingProvider, onTapped ->
        Box(Modifier.fillMaxSize().onSizeChanged { mapSize = it.width to it.height }) {
            if (styleUrl != null && !styleFailed) {
                FamilyMapView(
                    styleUrl = styleUrl,
                    markers = mapMarkers,
                    selectedId = state.selectedUserId,
                    cameraRequest = cameraRequest,
                    paddingProvider = paddingProvider,
                    contentDescription = pluralStringResource(R.plurals.map_cd, specs.size, specs.size),
                    onMarkerClick = { id ->
                        vm.select(id)
                        onTapped(id)
                    },
                    onStyleFailed = { styleFailed = true },
                    modifier = Modifier.fillMaxSize(),
                    places = placeShapes,
                    sosPins = sosPins,
                    onSosClick = { id -> sos.others.firstOrNull { it.actor.userId == id }?.let(::showSos) },
                    onLongPress = if (state.familyId != null) { lat, lon -> newPlaceAt = lat to lon } else null,
                )
            } else {
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text(stringResource(R.string.map_unavailable), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .onSizeChanged { topOverlayPx = it.height + with(density) { 12.dp.roundToPx() } },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { TrackingChip(status, queue, onOpenSharing) }
                    // Hold 3 s; the map attribution sits below it (see FamilyMapView).
                    SosHoldButton(
                        onTriggered = safety::sendSos,
                        size = 64.dp,
                        sending = safetyState.sendingSos,
                        showHint = false,
                    )
                }
                if (sos.own != null) OwnSosBanner(safetyState.resolving, safety::resolveSos)
                OthersSosBanner(sos.others, now, onShow = ::showSos)
            }
        }
    }

    if (wide) {
        val panelWidth = if (widthDp >= 900) 380.dp else 320.dp
        Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(panelWidth).fillMaxHeight(), tonalElevation = 1.dp) {
                panelContent(Modifier.fillMaxSize())
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                mapLayer({
                    CameraFit.padding(mapSize.first, mapSize.second, 0, 0, topOverlayPx, density.density)
                }) { id -> focus(id) }
            }
        }
    } else {
        val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
        val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
        var containerHeight by remember { mutableIntStateOf(0) }
        val peek = 176.dp
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetContent = { panelContent(Modifier.fillMaxWidth()) },
            modifier = Modifier.fillMaxSize().onSizeChanged { containerHeight = it.height },
        ) {
            mapLayer({
                val offset = runCatching { sheetState.requireOffset() }.getOrNull()
                val covered = if (offset != null) containerHeight - offset.roundToInt() else with(density) { peek.roundToPx() }
                CameraFit.padding(mapSize.first, mapSize.second, covered.coerceIn(0, containerHeight), 0, topOverlayPx, density.density)
            }) { id ->
                scope.launch {
                    if (sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand()
                    focus(id)
                }
            }
        }
        // Selecting a member in the expanded list collapses the sheet so the map is visible.
        LaunchedEffect(cameraRequest?.nonce) {
            if (cameraRequest != null && sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand()
        }
    }

    // Long-press on the map: new place at that point.
    newPlaceAt?.let { (lat, lon) ->
        PlaceDialog(
            title = stringResource(R.string.place_add_here),
            initial = null,
            positionNote = stringResource(R.string.place_position_pressed),
            busy = placeBusy,
            onConfirm = { name, radius, _ ->
                placeBusy = true
                scope.launch {
                    val error = vm.createPlace(name, radius, lat, lon)
                    placeBusy = false
                    newPlaceAt = null
                    snackbar.showSnackbar(if (error == null) placeAdded else uiErrorMessage(context, error))
                }
            },
            onDismiss = { newPlaceAt = null },
        )
    }
}

@Composable
private fun markerVisual(spec: MarkerSpec, member: MapMember?, state: MapUiState, now: Long): MarkerVisual {
    val stale = spec.freshness == Freshness.STALE
    val age = spec.recordedAtMs?.let { shortAgeText(it, now) } ?: stringResource(R.string.freshness_stale)
    val pill = when {
        spec.isSelf && spec.freshness == Freshness.LIVE -> stringResource(R.string.map_you)
        spec.isSelf -> stringResource(R.string.map_you_age, age)
        spec.freshness == Freshness.LIVE -> null
        else -> age
    }
    val avatar = member?.let { state.avatarFor(it) }
    val avatarKey = if (avatar != null) "a${member.avatarVersion ?: 0}" else "i"
    return MarkerVisual(
        key = "fc|${spec.userId}|${spec.colorHex}|$stale|${pill.orEmpty()}|$avatarKey",
        colorHex = spec.colorHex,
        stale = stale,
        initials = MarkerModel.initials(spec.name),
        avatar = avatar,
        pill = pill,
    )
}

@Composable
private fun TrackingChip(status: TrackingStatus, queue: Int, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val (label, dot) = when (status) {
        TrackingStatus.ON -> stringResource(R.string.map_chip_on) to colors.ok
        TrackingStatus.PAUSED -> stringResource(R.string.map_chip_paused) to colors.warning
        TrackingStatus.OFF -> stringResource(R.string.map_chip_off) to colors.neutral
    }
    val text = if (queue > 0) pluralStringResource(R.plurals.map_chip_queue, queue, label, queue) else label
    val action = stringResource(R.string.map_chip_action)
    ElevatedAssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 2) },
        leadingIcon = { Box(Modifier.size(10.dp).background(dot, CircleShape)) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                onClick(label = action) {
                    onClick()
                    true
                }
            },
    )
}

@Composable
private fun MemberPanel(
    state: MapUiState,
    specs: List<MarkerSpec>,
    now: Long,
    liveState: LiveConnectionState,
    onSelectFamily: (String) -> Unit,
    onShowAll: () -> Unit,
    onSelectMember: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val specById = specs.associateBy { it.userId }
    val members = state.live.members.sortedWith(
        compareByDescending<MapMember> { it.isSelf }
            .thenByDescending { specById.containsKey(it.userId) }
            .thenBy { it.displayName.lowercase() },
    )
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            PanelHeader(state, specs.isNotEmpty(), now, liveState, onSelectFamily, onShowAll)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        state.error?.let { err ->
            item { Text(uiErrorText(err), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        if (state.loaded && state.families.isEmpty()) {
            item { Text(stringResource(R.string.family_none), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
        } else if (state.loaded && specs.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.map_nobody),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(members, key = { it.userId }) { member ->
            MemberRow(
                member = member,
                spec = specById[member.userId],
                avatar = state.avatarFor(member),
                selected = member.userId == state.selectedUserId,
                now = now,
                onClick = { onSelectMember(member.userId) },
            )
            HorizontalDivider(Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun PanelHeader(
    state: MapUiState,
    hasMarkers: Boolean,
    now: Long,
    liveState: LiveConnectionState,
    onSelectFamily: (String) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FamilyTitle(state, onSelectFamily, Modifier.weight(1f))
            FilledTonalButton(onClick = onShowAll, enabled = hasMarkers) { Text(stringResource(R.string.map_show_all)) }
        }
        val sync = when (liveState) {
            LiveConnectionState.CONNECTED -> stringResource(R.string.map_live_on)
            LiveConnectionState.CONNECTING, LiveConnectionState.RECONNECTING -> stringResource(R.string.map_live_connecting)
            LiveConnectionState.IDLE -> null
        }
        val updated = state.lastUpdateMs?.let { stringResource(R.string.family_updated, relativeAgeText(it, now)) }
        val line = listOfNotNull(sync, updated).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FamilyTitle(state: MapUiState, onSelectFamily: (String) -> Unit, modifier: Modifier = Modifier) {
    val name = state.selectedFamily?.name ?: stringResource(R.string.family_title)
    if (state.families.size <= 1) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.semantics { heading() },
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    val cd = stringResource(R.string.map_family_picker_cd, name)
    Box(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = cd }) {
            Text("$name ▾", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.families.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.name) },
                    onClick = {
                        open = false
                        onSelectFamily(f.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: MapMember,
    spec: MarkerSpec?,
    avatar: android.graphics.Bitmap?,
    selected: Boolean,
    now: Long,
    onClick: () -> Unit,
) {
    val showLabel = stringResource(R.string.map_show_on_map)
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    val rowModifier = if (spec != null) base.clickable(role = Role.Button, onClickLabel = showLabel, onClick = onClick) else base
    Row(
        rowModifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemberAvatar(
            name = member.displayName,
            colorHex = member.color,
            avatar = avatar,
            stale = spec == null || spec.freshness == Freshness.STALE,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (member.isSelf) stringResource(R.string.family_you, member.displayName) else member.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            if (spec != null) {
                val details = buildList {
                    spec.recordedAtMs?.let { add(relativeAgeText(it, now)) }
                    add(stringResource(R.string.map_accuracy, spec.accuracyM.roundToInt()))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodyMedium)
                val extra = buildList {
                    spec.batteryPct?.let { pct ->
                        add(
                            if (spec.batteryCharging == true) {
                                stringResource(R.string.family_battery_charging, pct)
                            } else {
                                stringResource(R.string.family_battery, pct)
                            },
                        )
                    }
                    if (spec.showMotion) motionText(spec.motion)?.let(::add)
                    if (spec.source == MarkerSource.DEVICE) add(stringResource(R.string.map_from_device))
                }
                if (extra.isNotEmpty()) {
                    Text(
                        extra.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(if (member.isSelf) R.string.family_self_no_location else R.string.map_not_visible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (spec != null) FreshnessBadge(spec.freshness)
    }
}
