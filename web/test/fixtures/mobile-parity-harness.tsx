import React, { useCallback, useState } from "react";
import { createRoot } from "react-dom/client";
import { ConfigProvider, Input } from "antd";
import { CanvasNode } from "@/components/canvas/canvas-node";
import { CanvasNodeType, type CanvasNodeData } from "@/types/canvas";
import { applyMobileDensity } from "@/lib/mobile-platform";
import { getWorkspaceAntThemeConfig } from "@/lib/app-theme";
const noop = () => {};
function Harness() {
    const [node, setNode] = useState<CanvasNodeData>({ id:"resize", type:CanvasNodeType.Text, title:"触摸测试", position:{x:80,y:90}, width:320,height:240 });
    const resize = useCallback((_id:string,width:number,height:number,position?:{x:number;y:number}) => setNode(current=>({...current,width,height,position:position||current.position})), []);
    return <ConfigProvider theme={getWorkspaceAntThemeConfig()}>
        <Input aria-label="模型地址" style={{width:220}} />
        <button id="comfortable" onClick={()=>applyMobileDensity("comfortable")}>舒适</button>
        <output id="size">{JSON.stringify({width:node.width,height:node.height})}</output>
        <CanvasNode data={node} scale={1} isSelected isRelated={false} isFocusRelated={false} isConnectionTarget={false} onMouseDown={noop} onHoverStart={noop} onHoverEnd={noop} onConnectStart={noop} onResize={resize} onTitleChange={noop} onContentChange={noop} onContextMenu={noop}/>
    </ConfigProvider>;
}
applyMobileDensity("compact");
createRoot(document.getElementById("root")!).render(<Harness/>);

