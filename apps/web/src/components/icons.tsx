// Simple line icons (24px grid, currentColor). Hand-drawn to avoid an icon dependency.

const base = { width: 24, height: 24, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };

export const MapIcon = () => (
  <svg {...base}>
    <path d="M9 4 3 6.5v13.5l6-2.5 6 2.5 6-2.5V4l-6 2.5L9 4Z" />
    <path d="M9 4v13.5M15 6.5V20" />
  </svg>
);

export const FamilyIcon = () => (
  <svg {...base}>
    <circle cx="8" cy="8" r="3" />
    <circle cx="16.5" cy="9.5" r="2.5" />
    <path d="M2.5 19c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5M13.5 15.2c.9-.8 1.9-1.2 3-1.2 2.3 0 4 1.5 4.5 4.5" />
  </svg>
);

export const ShieldIcon = () => (
  <svg {...base}>
    <path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.2 7.5 9.5 4.4-1.3 7.5-4.9 7.5-9.5V6L12 3Z" />
    <path d="m9 12 2 2 4-4" />
  </svg>
);

export const BellIcon = () => (
  <svg {...base}>
    <path d="M6 9.5a6 6 0 0 1 12 0c0 4.5 1.8 6.5 2.5 7.5h-17C4.2 16 6 14 6 9.5Z" />
    <path d="M10 20a2.2 2.2 0 0 0 4 0" />
  </svg>
);

export const PinIcon = () => (
  <svg {...base}>
    <path d="M12 21s-7-6.1-7-11.5A7 7 0 0 1 19 9.5C19 14.9 12 21 12 21Z" />
    <circle cx="12" cy="9.5" r="2.5" />
  </svg>
);

export const CheckPinIcon = () => (
  <svg {...base}>
    <path d="M12 21s-7-6.1-7-11.5A7 7 0 0 1 19 9.5C19 14.9 12 21 12 21Z" />
    <path d="m9 10 2 2 4-4" />
  </svg>
);

export const BatteryIcon = () => (
  <svg {...base}>
    <rect x="3" y="7" width="16" height="10" rx="2" />
    <path d="M22 11v2M6.5 10v4" />
  </svg>
);

