import type { SVGProps } from "react";

/**
 * A mark for Lark in its product colors: the wing over the paper plane. It is drawn inline for the
 * same reason the Drive and SharePoint marks are: no runtime asset fetch.
 */
export function LarkIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg" {...props}>
      <path
        d="M13.5 8h16.2c2.6 3 4.6 6.6 5.8 10.6l-9.9 5.9C23.7 17.8 19.3 12 13.5 8Z"
        fill="#00D6B9"
      />
      <path
        d="M6 18.6c6.2 6.2 13.6 10.8 21.6 13.6 2.6.9 5.2.6 7.4-.9L43 26c-3.6 7.6-11.3 13-20.2 13-6.2 0-12-2.4-16.3-6.6V18.6Z"
        fill="#3370FF"
      />
      <path
        d="M20.7 28.1c2.1 1.2 4.4 2.2 6.8 3 2.6.9 5.2.6 7.4-.9l7.7-5.6c1.4-1 2.9-1.8 4.4-2.4-2.1-1.3-4.6-2-7.2-2-2.5 0-4.9.7-7 2l-12.1 5.9Z"
        fill="#133C9A"
      />
    </svg>
  );
}
