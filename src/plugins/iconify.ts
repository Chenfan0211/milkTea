import { addCollection, type IconifyJSON } from '@iconify/vue/offline';
import offlineIconCollections from '@/assets/iconify/offline-icons.json';

/** Register the build-time extracted icons so the runtime never falls back to the public Iconify API. */
export function setupIconifyOffline() {
  offlineIconCollections.forEach(collection => addCollection(collection as unknown as IconifyJSON));
}
