import { useAppTranslation } from "@/i18n/use-app-translation";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Project } from "./chat-projects-api";
import { ProjectConversationList } from "./project-conversation-list";
import { ProjectFiles } from "./project-files";

/** Under the Project composer: its conversations and its files, one at a time. */
export function ProjectTabs({ project }: { project: Project }) {
  const ui = useAppTranslation();
  return (
    <Tabs defaultValue="chats" className="mt-6">
      <TabsList variant="line" slider aria-label={ui("Nội dung dự án")}>
        <TabsTrigger value="chats" focus="soft">
          {ui("Trò chuyện")}
        </TabsTrigger>
        <TabsTrigger value="files" focus="soft">
          {project.fileIds.length > 0
            ? ui("Tệp ({{count}})", { count: project.fileIds.length })
            : ui("Tệp")}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="chats" animated>
        <ProjectConversationList projectId={project.id} />
      </TabsContent>
      <TabsContent value="files" animated>
        <ProjectFiles project={project} />
      </TabsContent>
    </Tabs>
  );
}
