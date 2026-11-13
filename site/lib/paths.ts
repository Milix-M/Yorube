export const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "/Yorube";

// next/link adds basePath itself. Plain anchors, images and metadata need it explicitly.
export const assetPath = (path: string) => `${basePath}${path}`;

export const languages = (page = "") => ({
  ja: assetPath(page ? `/${page}/` : "/"),
  en: assetPath(page ? `/en/${page}/` : "/en/"),
});
