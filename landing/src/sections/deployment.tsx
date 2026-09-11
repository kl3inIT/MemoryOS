import { ArrowRight } from "lucide-react";
import { Section } from "@/components/section";
import { deployment, type DeploymentHost } from "@/content";

type HostCardProps = {
  host: DeploymentHost;
};

function HostCard({ host }: HostCardProps) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-raised p-4">
      <p className="font-main-ui-action text-content-primary">{host.title}</p>
      <p className="font-secondary-body text-content-muted">{host.platform}</p>
      <ul className="mt-3 space-y-1.5 font-main-ui-body text-content-secondary">
        {host.services.map((service) => (
          <li key={service}>{service}</li>
        ))}
      </ul>
    </div>
  );
}

function Deployment() {
  const { caption, people, account, network, application, data, model, sharedServices } =
    deployment;

  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <figure className="rounded-2xl bg-surface-canvas p-4 sm:p-6 lg:p-8">
        <figcaption className="font-main-ui-action text-content-secondary">{caption}</figcaption>
        <div className="mt-5 grid items-center gap-3 lg:grid-cols-[11rem_auto_minmax(0,1fr)]">
          <div className="rounded-lg border border-border-subtle bg-surface-raised p-4">
            <p className="font-main-ui-action text-content-primary">{people.title}</p>
            <p className="mt-1 font-secondary-body text-content-muted">{people.description}</p>
          </div>
          <ArrowRight
            aria-hidden="true"
            className="mx-auto size-5 rotate-90 text-content-muted lg:rotate-0"
          />
          <div className="rounded-xl border border-border-default bg-surface-base p-4 sm:p-5">
            <p className="font-main-ui-action text-content-primary">{account}</p>
            <div className="mt-4 grid gap-3 xl:grid-cols-[minmax(0,1fr)_13rem]">
              <div className="rounded-lg border border-dashed border-border-strong p-3 sm:p-4">
                <p className="font-secondary-action text-content-muted">{network}</p>
                <div className="mt-3 grid gap-3 md:grid-cols-2">
                  <HostCard host={application} />
                  <HostCard host={data} />
                </div>
              </div>
              <HostCard host={model} />
            </div>
            <ul className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {sharedServices.map((service) => (
                <li
                  key={service.title}
                  className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2.5"
                >
                  <p className="font-main-ui-action text-content-primary">{service.title}</p>
                  <p className="font-secondary-body text-content-muted">{service.description}</p>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </figure>
    </Section>
  );
}

export { Deployment };
