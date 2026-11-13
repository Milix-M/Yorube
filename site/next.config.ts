import type { NextConfig } from "next";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "/Yorube";
if (basePath && (!basePath.startsWith("/") || basePath.endsWith("/"))) {
  throw new Error(
    "NEXT_PUBLIC_BASE_PATH must be empty or start with / without a trailing slash.",
  );
}

const nextConfig: NextConfig = {
  output: "export",
  basePath,
  trailingSlash: true,
  images: { unoptimized: true },
};

export default nextConfig;
