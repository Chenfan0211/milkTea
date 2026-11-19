import { compressAdminImage, MAX_ADMIN_IMAGE_BYTES } from '@/utils/image-compression';

interface BannerImageOptions {
  maxWidth: number;
  maxHeight: number;
  minWidth?: number;
  minHeight?: number;
}

export const MAX_BANNER_BYTES = MAX_ADMIN_IMAGE_BYTES;

export async function compressBannerImage(file: File, options: BannerImageOptions): Promise<File> {
  return compressAdminImage(file, options);
}
