const MARKER_ICON_PATH = '/assets/icons/lucide/store-map-pin.svg';
const ACTIVE_MARKER_ICON_PATH = '/assets/icons/lucide/store-map-pin-active.svg';
const MARKER_BORDER_COLOR = '#E9EAE5';
const ACTIVE_MARKER_BORDER_COLOR = '#53882C';
const CALLOUT_TEXT_COLOR = '#2F302D';
const CALLOUT_BG_COLOR = '#FFFFFF';

function buildStoreMarkers(stores, currentStoreId) {
  return (stores || []).map((store, index) => {
    const active = store.id === currentStoreId;
    return {
      id: index + 1,
      storeId: store.id,
      latitude: store.latitude,
      longitude: store.longitude,
      iconPath: active ? ACTIVE_MARKER_ICON_PATH : MARKER_ICON_PATH,
      width: 40,
      height: 40,
      zIndex: active ? 3 : 2,
      anchor: { x: 0.5, y: 1 },
      callout: {
        content: store.name,
        display: 'ALWAYS',
        textAlign: 'center',
        color: CALLOUT_TEXT_COLOR,
        fontSize: 12,
        bgColor: CALLOUT_BG_COLOR,
        borderColor: active ? ACTIVE_MARKER_BORDER_COLOR : MARKER_BORDER_COLOR,
        borderWidth: 1,
        borderRadius: 8,
        padding: 6
      }
    };
  });
}

module.exports = {
  buildStoreMarkers
};
