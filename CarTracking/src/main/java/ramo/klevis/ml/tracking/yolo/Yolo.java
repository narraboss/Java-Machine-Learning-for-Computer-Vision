package ramo.klevis.ml.tracking.yolo;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;
import org.datavec.image.loader.NativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.layers.objdetect.DetectedObject;
import org.deeplearning4j.nn.layers.objdetect.Yolo2OutputLayer;
import org.deeplearning4j.nn.layers.objdetect.YoloUtils;
import org.deeplearning4j.zoo.model.TinyYOLO;
import org.deeplearning4j.zoo.model.YOLO2;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.threadly.concurrent.collections.ConcurrentArrayList;
import ramo.klevis.ml.tracking.cifar.TrainCifar10Model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.bytedeco.javacpp.opencv_core.FONT_HERSHEY_DUPLEX;
import static org.bytedeco.javacpp.opencv_core.Mat;
import static org.bytedeco.javacpp.opencv_core.Point;
import static org.bytedeco.javacpp.opencv_core.Scalar;
import static org.bytedeco.javacpp.opencv_highgui.imshow;
import static org.bytedeco.javacpp.opencv_imgproc.putText;
import static org.bytedeco.javacpp.opencv_imgproc.rectangle;

@Slf4j
public class Yolo {

    private static final double DETECTION_THRESHOLD = 0.7;
    private static final double THRESHOLD = 0.85;
    public final String[] COCO_CLASSES = {"person", "bicycle", "car", "motorbike", "aeroplane", "bus", "train",
            "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag",
            "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
            "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "sofa", "pottedplant", "bed", "diningtable", "toilet", "tvmonitor", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
            "teddy bear", "hair drier", "toothbrush"};
    private final Map<String, Stack<Mat>> stackMap = new ConcurrentHashMap<>();
    private final String[] TINY_COCO_CLASSES = {"aeroplane", "bicycle", "bird", "boat", "bottle", "bus", "car",
            "cat", "chair", "cow", "diningtable", "dog", "horse", "motorbike", "person", "pottedplant",
            "sheep", "sofa", "train", "tvmonitor"};
    private TrainCifar10Model trainCifar10Model = new TrainCifar10Model();
    private Speed selectedSpeed;
    private boolean outputFrames;
    private volatile List<MarkedObject> predictedObjects = new ConcurrentArrayList<>();
    private volatile Set<MarkedObject> previousPredictedObjects = new TreeSet<>();

    private HashMap<Integer, String> map;
    private HashMap<String, String> groupMap;
    private Map<String, ComputationGraph> modelsMap = new ConcurrentHashMap<>();
    private NativeImageLoader loader;

    public Yolo() throws IOException {
        trainCifar10Model.loadTrainedModel();
    }


    public void initialize(Speed selectedSpeed, boolean yolo, String windowName,
                           boolean outputFrames) throws IOException {
        this.selectedSpeed = selectedSpeed;
        this.outputFrames = outputFrames;
        stackMap.put(windowName, new Stack<>());
        ComputationGraph yoloV2;
        if (yolo) {
            //real yolo v2
            yoloV2 = (ComputationGraph) YOLO2.builder().build().initPretrained();
            prepareYOLOLabels();
        } else {
            //tiny yolo
            yoloV2 = (ComputationGraph) TinyYOLO.builder().build().initPretrained();
            prepareTinyYOLOLabels();
        }
        modelsMap.put(windowName, yoloV2);
        loader = new NativeImageLoader(selectedSpeed.height, selectedSpeed.width, 3);
        warmUp(yoloV2);
    }

    private void warmUp(ComputationGraph model) throws IOException {
        Yolo2OutputLayer outputLayer = (Yolo2OutputLayer) model.getOutputLayer(0);
        BufferedImage read = ImageIO.read(new File("AutonomousDriving/src/main/resources/sample.jpg"));
        INDArray indArray = prepareImage(loader.asMatrix(read));
        INDArray results = model.outputSingle(indArray);
        outputLayer.getPredictedObjects(results, DETECTION_THRESHOLD);
    }

    public void push(Mat matFrame, String windowName) {
        stackMap.get(windowName).push(matFrame);
    }

    public void drawBoundingBoxesRectangles(Frame frame, Mat matFrame, String windowName) throws Exception {
        if (invalidData(frame, matFrame) || outputFrames) return;
        if (previousPredictedObjects == null) {
            previousPredictedObjects.addAll(predictedObjects);
        }
        ArrayList<MarkedObject> detectedObjects = predictedObjects == null ? new ArrayList<>() : new ArrayList<>(predictedObjects);

        Set<String> usedIds = new HashSet<>();
        for (MarkedObject markedObjects1 : detectedObjects) {
            try {
                createBoundingBoxRectangle(matFrame, selectedSpeed.width, selectedSpeed.height, markedObjects1, usedIds);
                imshow(windowName, matFrame);
            } catch (Exception e) {
                e.printStackTrace();
                log.error("Problem with out of the bounds image");
                imshow(windowName, matFrame);
            }
        }
        previousPredictedObjects.addAll(detectedObjects);

        if (detectedObjects.isEmpty())
            imshow(windowName, matFrame);

    }

    private boolean invalidData(Frame frame, Mat matFrame) {
        return predictedObjects == null || matFrame == null || frame == null;
    }

    public void predictBoundingBoxes(String windowName) throws IOException {
        long start = System.currentTimeMillis();
        Yolo2OutputLayer outputLayer = (Yolo2OutputLayer) modelsMap.get(windowName).getOutputLayer(0);
        Mat matFrame = stackMap.get(windowName).pop();
        INDArray indArray = prepareImage(matFrame);
        log.info("stack of frames size " + stackMap.get(windowName).size());
        if (indArray == null) {
            return;
        }

        INDArray results = modelsMap.get(windowName).outputSingle(indArray);
        if (results == null) {
            return;
        }
        List<DetectedObject> predictedObjects = outputLayer.getPredictedObjects(results, DETECTION_THRESHOLD);
        YoloUtils.nms(predictedObjects, 0.5);
        List<MarkedObject> markedObjects = predictedObjects.stream()
                .filter(e -> groupMap.get(map.get(e.getPredictedClass())) != null)
                .map(e -> {
                    try {
                        return new MarkedObject(e, trainCifar10Model.getEmbeddings(matFrame, e, selectedSpeed),
                                System.currentTimeMillis(), matFrame);
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                    return null;
                }).collect(Collectors.toCollection(() -> new ConcurrentArrayList<>()));

        this.predictedObjects = markedObjects;
        log.info("stack of predictions size " + this.predictedObjects.size());
        log.info("Prediction time " + (System.currentTimeMillis() - start) / 1000d);
    }

    private INDArray prepareImage(Mat frame) throws IOException {
        if (frame == null) {
            return null;
        }
        ImagePreProcessingScaler imagePreProcessingScaler = new ImagePreProcessingScaler(0, 1);

        INDArray indArray = loader.asMatrix(frame);
        if (indArray == null) {
            return null;
        }
        imagePreProcessingScaler.transform(indArray);
        return indArray;
    }

    private INDArray prepareImage(INDArray indArray) throws IOException {

        if (indArray == null) {
            return null;
        }
        ImagePreProcessingScaler imagePreProcessingScaler = new ImagePreProcessingScaler(0, 1);
        imagePreProcessingScaler.transform(indArray);
        return indArray;
    }

    private void prepareYOLOLabels() {
        prepareLabels(COCO_CLASSES);
    }

    private void prepareLabels(String[] coco_classes) {
        if (map == null) {
            groupMap = new HashMap<>();
            groupMap.put("car", "C");
            groupMap.put("bus", "C");
            groupMap.put("truck", "C");
            int i = 0;
            map = new HashMap<>();
            for (String s1 : coco_classes) {
                map.put(i++, s1);
            }
        }
    }

    private void prepareTinyYOLOLabels() {
        prepareLabels(TINY_COCO_CLASSES);
    }

    private void createBoundingBoxRectangle(Mat file, int w, int h, MarkedObject obj,
                                            Set<String> usedIds) throws Exception {
        double[] xy1 = obj.getDetectedObject().getTopLeftXY();
        double[] xy2 = obj.getDetectedObject().getBottomRightXY();
        String id;
        MarkedObject minDistanceMarkedObject = null;
        double min = Double.MAX_VALUE;

        for (MarkedObject predictedObject : previousPredictedObjects) {

            double distance = predictedObject.getL2Norm().distance2(obj.getL2Norm());
            if (YoloUtils.iou(obj.getDetectedObject(),
                    predictedObject.getDetectedObject()) >= 0.4 && distance <= 1.1) {
                minDistanceMarkedObject = predictedObject;
                obj.setId(minDistanceMarkedObject.getId());
                break;

            }
            System.out.println("distance 1 = " + distance);
            if (distance < min && distance < THRESHOLD &&
                    !usedIds.contains(predictedObject.getId())) {
                minDistanceMarkedObject = predictedObject;
                min = distance;
                obj.setId(minDistanceMarkedObject.getId());
            }
        }
        if (minDistanceMarkedObject == null) {
            minDistanceMarkedObject = obj;
        }

        id = minDistanceMarkedObject.getId();
        usedIds.add(id);

        int predictedClass = obj.getDetectedObject().getPredictedClass();

        int x1 = (int) Math.round(w * xy1[0] / selectedSpeed.gridWidth);
        int y1 = (int) Math.round(h * xy1[1] / selectedSpeed.gridHeight);
        int x2 = (int) Math.round(w * xy2[0] / selectedSpeed.gridWidth);
        int y2 = (int) Math.round(h * xy2[1] / selectedSpeed.gridHeight);

        rectangle(file, new Point(x1, y1), new Point(x2, y2), Scalar.BLUE);
        putText(file, groupMap.get(map.get(predictedClass)) + "-" + id, new Point(x1 + 2, y2 - 2), FONT_HERSHEY_DUPLEX, 0.8, Scalar.GREEN);

    }

}