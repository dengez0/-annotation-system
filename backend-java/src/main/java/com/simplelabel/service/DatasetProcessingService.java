package com.simplelabel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import com.simplelabel.task.TaskService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

@Service
public class DatasetProcessingService {
    private final AppPaths paths; private final AnnotationService annotations; private final TaskService tasks; private final ObjectMapper mapper;
    public DatasetProcessingService(AppPaths paths, AnnotationService annotations, TaskService tasks, ObjectMapper mapper) { this.paths=paths; this.annotations=annotations; this.tasks=tasks; this.mapper=mapper; }

    public List<String> labels(String main, String sub) throws IOException { return labelsFor(annotations.projectPath(main, sub)); }
    public String start(String main, String sub, List<String> order) throws IOException {
        Path source=annotations.projectPath(main, sub); if(!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND,"Project not found");
        List<String> expected=labelsFor(source); if(order==null || order.size()!=expected.size() || new HashSet<>(order).size()!=order.size() || !new HashSet<>(order).equals(new HashSet<>(expected))) throw new IllegalArgumentException("Select every non-mask label exactly once before processing");
        String id=tasks.create("data_processing"); tasks.submit(()->run(id,source,main,sub,order)); return id;
    }
    private List<String> labelsFor(Path source) throws IOException { Set<String> result=new TreeSet<>(); for(Path image:images(source)){Path json=image.resolveSibling(AnnotationService.stem(image.getFileName().toString())+".json"); if(!Files.isRegularFile(json)) continue; try { for(JsonNode shape:mapper.readTree(json.toFile()).path("shapes")){String label=shape.path("label").asText(""); if(!label.isBlank()&&!label.equals("mask")) result.add(label);}}catch(Exception ignored){}} return new ArrayList<>(result); }
    private List<Path> images(Path root) throws IOException { try(Stream<Path> s=Files.walk(root)){return s.filter(Files::isRegularFile).filter(p->AnnotationService.isImage(p.getFileName().toString())).sorted().toList();} }
    private void run(String id,Path source,String main,String sub,List<String> labels){ TaskService.TaskState task=tasks.state(id); if(task==null)return; Path staging=null; try {
        String name=sub+"_"+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()); Path parent=paths.processed().resolve(main); Files.createDirectories(parent); staging=Files.createTempDirectory(parent,"."+name+"-"); List<Path> input=images(source); task.total(input.size());
        tar(source,staging.resolve(name+".tar.bak")); Path dataset=staging.resolve("dataset"); Files.createDirectories(dataset); Files.writeString(dataset.resolve("classes.txt"),String.join("\n",labels)+(labels.isEmpty()?"":"\n"),StandardCharsets.UTF_8);
        Map<String,Integer> labelIds=new LinkedHashMap<>(); for(int i=0;i<labels.size();i++)labelIds.put(labels.get(i),i); List<Map<String,String>> failures=new ArrayList<>(); int delivered=0,empty=0;
        for(int i=0;i<input.size();i++){if(task.cancelled())return; Path image=input.get(i); Path relative=source.relativize(image), out=dataset.resolve(relative); Files.createDirectories(out.getParent()); try { BufferedImage processed=readRgb(image); Path json=image.resolveSibling(AnnotationService.stem(image.getFileName().toString())+".json"); List<String> lines=new ArrayList<>(); if(Files.isRegularFile(json)) lines=applyAndExport(processed,mapper.readTree(json.toFile()),labelIds); else empty++; ImageIO.write(processed, extension(out.getFileName().toString()),out.toFile()); Files.writeString(out.resolveSibling(AnnotationService.stem(out.getFileName().toString())+".txt"),String.join("\n",lines)+(lines.isEmpty()?"":"\n"),StandardCharsets.UTF_8); delivered++; } catch(Exception e){failures.add(Map.of("file",relative.toString().replace('\\','/'),"error",String.valueOf(e.getMessage()))); try{Files.deleteIfExists(out);}catch(Exception ignored){}} task.progress(i+1,delivered); }
        ObjectNode report=mapper.createObjectNode(); report.put("source","data/"+main+"/"+sub); report.put("mask_label","mask"); report.put("total_images",input.size()); report.put("delivered",delivered); report.put("empty_labels",empty); report.set("labels",mapper.valueToTree(labels)); report.set("failed",mapper.valueToTree(failures)); mapper.writerWithDefaultPrettyPrinter().writeValue(staging.resolve("report.json").toFile(),report); Path finalDir=parent.resolve(name); Files.move(staging,finalDir,StandardCopyOption.ATOMIC_MOVE); task.complete();
    } catch(Exception e){task.fail(e);} finally {if(staging!=null&&Files.exists(staging))try(Stream<Path>s=Files.walk(staging)){s.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}} }
    private static BufferedImage readRgb(Path path)throws IOException{BufferedImage original=ImageIO.read(path.toFile());if(original==null)throw new IOException("Cannot read image");BufferedImage copy=new BufferedImage(original.getWidth(),original.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=copy.createGraphics();g.drawImage(original,0,0,null);g.dispose();return copy;}
    private static String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"jpg":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    private List<String> applyAndExport(BufferedImage image,JsonNode data,Map<String,Integer> ids){List<String> lines=new ArrayList<>();Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);for(JsonNode shape:data.path("shapes")){String label=shape.path("label").asText("");List<double[]> pts=new ArrayList<>();for(JsonNode point:shape.path("points"))if(point.size()>=2)pts.add(new double[]{point.get(0).asDouble(),point.get(1).asDouble()});if(label.equals("mask")){if(pts.size()>=3){Path2D p=new Path2D.Double();p.moveTo(pts.get(0)[0],pts.get(0)[1]);for(int i=1;i<pts.size();i++)p.lineTo(pts.get(i)[0],pts.get(i)[1]);p.closePath();g.fill(p);}else if(pts.size()>=2){double x=Math.min(pts.get(0)[0],pts.get(1)[0]),y=Math.min(pts.get(0)[1],pts.get(1)[1]);g.fillRect((int)x,(int)y,(int)Math.abs(pts.get(0)[0]-pts.get(1)[0]),(int)Math.abs(pts.get(0)[1]-pts.get(1)[1]));}continue;}if(!ids.containsKey(label)||pts.size()<2)continue;double minX=pts.stream().mapToDouble(p->p[0]).min().orElse(0),maxX=pts.stream().mapToDouble(p->p[0]).max().orElse(0),minY=pts.stream().mapToDouble(p->p[1]).min().orElse(0),maxY=pts.stream().mapToDouble(p->p[1]).max().orElse(0);if(maxX>minX&&maxY>minY)lines.add(String.format(Locale.ROOT,"%d %.6f %.6f %.6f %.6f",ids.get(label),((minX+maxX)/2)/image.getWidth(),((minY+maxY)/2)/image.getHeight(),(maxX-minX)/image.getWidth(),(maxY-minY)/image.getHeight()));}g.dispose();return lines;}
    private static void tar(Path root,Path archive)throws IOException{try(OutputStream output=Files.newOutputStream(archive);TarArchiveOutputStream tar=new TarArchiveOutputStream(output);Stream<Path> stream=Files.walk(root)){for(Path path:stream.filter(Files::isRegularFile).toList()){String entry=root.getFileName()+"/"+root.relativize(path).toString().replace('\\','/');TarArchiveEntry item=new TarArchiveEntry(path.toFile(),entry);tar.putArchiveEntry(item);Files.copy(path,tar);tar.closeArchiveEntry();}tar.finish();}}
}
