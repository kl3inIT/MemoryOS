/*
 * Icon paths adapted from Onyx Opal (MIT), commit 06aa2b09.
 * Copyright (c) 2023-present DanswerAI, Inc.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import type { ComponentProps } from "react";

type IconProps = ComponentProps<"svg">;

export function OnyxUserIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M13.3333 14V12.6667C13.3333 11.9594 13.0524 11.2811 12.5523 10.781C12.0522 10.281 11.3739 10 10.6667 10H5.33334C4.62609 10 3.94782 10.281 3.44772 10.781C2.94762 11.2811 2.66667 11.9594 2.66667 12.6667V14M10.6667 4.66667C10.6667 6.13943 9.47276 7.33333 8.00001 7.33333C6.52725 7.33333 5.33334 6.13943 5.33334 4.66667C5.33334 3.19391 6.52725 2 8.00001 2C9.47276 2 10.6667 3.19391 10.6667 4.66667Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUsersIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M11.3333 14V12.6667C11.3333 11.9594 11.0524 11.2811 10.5523 10.781C10.0522 10.281 9.3739 10 8.66666 10H3.33332C2.62608 10 1.9478 10.281 1.44771 10.781C0.947608 11.2811 0.666656 11.9594 0.666656 12.6667V14M15.3333 14V12.6667C15.3329 12.0758 15.1362 11.5018 14.7742 11.0349C14.4122 10.5679 13.9054 10.2344 13.3333 10.0867M10.6667 2.08667C11.2403 2.23353 11.7487 2.56713 12.1117 3.03487C12.4748 3.50261 12.6719 4.07789 12.6719 4.67C12.6719 5.26211 12.4748 5.83739 12.1117 6.30513C11.7487 6.77287 11.2403 7.10647 10.6667 7.25333M8.66666 4.66667C8.66666 6.13943 7.47275 7.33333 5.99999 7.33333C4.52723 7.33333 3.33332 6.13943 3.33332 4.66667C3.33332 3.19391 4.52723 2 5.99999 2C7.47275 2 8.66666 3.19391 8.66666 4.66667Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUserManageIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 15 14" fill="none" stroke="currentColor" {...props}>
      <path
        d="M0.75 12.75C0.75 12.4167 0.75 12.0833 0.75 11.75C0.750002 10.0931 2.09316 8.75 3.75002 8.75L5.75 8.75M12.25 11.25L13.2981 12.2981M12.25 11.25C12.5916 10.9084 12.7499 10.4481 12.75 10.0004M12.25 11.25C11.9083 11.5917 11.4479 11.75 11 11.75M9.75 11.25L8.7019 12.2981M9.75 11.25C10.0917 11.5917 10.5521 11.75 11 11.75M9.75 11.25C9.4084 10.9084 9.25011 10.4481 9.25 10.0004M9.75 8.75L8.7019 7.70193M9.75 8.75C10.0917 8.40829 10.5521 8.25 11 8.25M9.75 8.75C9.40818 9.09182 9.24989 9.55242 9.25 10.0004M12.25 8.75L13.2981 7.70193M12.25 8.75C12.5918 9.09182 12.7501 9.55242 12.75 10.0004M12.25 8.75C11.9083 8.40829 11.4479 8.25 11 8.25M12.75 10.0004L14.25 10M11 13.25V11.75M11 6.75V8.25M7.75 10L9.25 10.0004M8.5 3.5C8.5 5.01878 7.26878 6.25 5.75 6.25C4.23122 6.25 3 5.01878 3 3.5C3 1.98122 4.23122 0.75 5.75 0.75C7.26878 0.75 8.5 1.98122 8.5 3.5Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUserPlusIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M11 14C11 13.6667 11 13.3333 11 13C11 11.3431 9.65684 10 7.99998 10H4.00002C2.34316 10 1 11.3431 1 13C1 13.3333 1 13.6667 1 14M10.75 7.50005L12.75 7.50007M12.75 7.50007H14.75M12.75 7.50007V9.5M12.75 7.50007V5.5M8.75 4.75C8.75 6.26878 7.51878 7.5 6 7.5C4.48122 7.5 3.25 6.26878 3.25 4.75C3.25 3.23122 4.48122 2 6 2C7.51878 2 8.75 3.23122 8.75 4.75Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUserCheckIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M11 14C11 13.6667 11 13.3333 11 13C11 11.3431 9.65684 10 7.99998 10H4.00002C2.34316 10 1 11.3431 1 13C1 13.3333 1 13.6667 1 14M10.75 7.49999L12.25 9L15 6.24999M8.75 4.75C8.75 6.26878 7.51878 7.5 6 7.5C4.48122 7.5 3.25 6.26878 3.25 4.75C3.25 3.23122 4.48122 2 6 2C7.51878 2 8.75 3.23122 8.75 4.75Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUserXIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M11 14C11 13.6667 11 13.3333 11 13C11 11.3431 9.65684 10 7.99998 10H4.00002C2.34316 10 1 11.3431 1 13C1 13.3333 1 13.6667 1 14M11.5 8.5L13.25 6.75M13.25 6.75L15 5M13.25 6.75L15 8.5M13.25 6.75L11.5 5M8.75 4.75C8.75 6.26878 7.51878 7.5 6 7.5C4.48122 7.5 3.25 6.26878 3.25 4.75C3.25 3.23122 4.48122 2 6 2C7.51878 2 8.75 3.23122 8.75 4.75Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function OnyxUserShieldIcon(props: IconProps) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" {...props}>
      <path
        d="M1 14C1 13.6667 1 13.3333 1 13C1 11.3431 2.34316 10 4.00002 10H7M8.75 4.75C8.75 6.26878 7.51878 7.5 6 7.5C4.48122 7.5 3.25 6.26878 3.25 4.75C3.25 3.23122 4.48122 2 6 2C7.51878 2 8.75 3.23122 8.75 4.75ZM12 14.5C12 14.5 14.5 13.25 14.5 11.375V9L12 8L9.5 9V11.375C9.5 13.25 12 14.5 12 14.5Z"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
